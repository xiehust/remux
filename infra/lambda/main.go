// Command lambda is the API Gateway WebSocket connection-lifecycle and routing
// handler for the serverless deployment of the Remux relay. It manages the
// DynamoDB device/connection registry and routes app "open" requests to the
// target device's connection. It mirrors the control-plane behaviour of the
// self-hosted Go relay (relay/), implemented against API Gateway + DynamoDB.
//
// Built as a Go custom runtime (provided.al2023): the binary must be named
// `bootstrap` in the deployment zip.
package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"time"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-lambda-go/lambda"
	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/apigatewaymanagementapi"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	ddbtypes "github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

// envelope is the control-plane message shape (mirror of proto.Envelope).
type envelope struct {
	Type string          `json:"type"`
	Data json.RawMessage `json:"data,omitempty"`
}

type registerData struct {
	DeviceID string `json:"deviceId"`
	Name     string `json:"name"`
	Token    string `json:"token"`
	Platform string `json:"platform"`
}

type openData struct {
	DeviceID  string `json:"deviceId,omitempty"`
	SessionID string `json:"sessionId,omitempty"`
}

type deviceInfo struct {
	DeviceID string `json:"deviceId"`
	Name     string `json:"name"`
	Platform string `json:"platform"`
	Online   bool   `json:"online"`
}

var (
	table = os.Getenv("REGISTRY_TABLE")
	token = os.Getenv("RELAY_TOKEN")
	ddb   *dynamodb.Client
)

func main() {
	cfg, err := awsconfig.LoadDefaultConfig(context.Background())
	if err != nil {
		log.Fatalf("load aws config: %v", err)
	}
	ddb = dynamodb.NewFromConfig(cfg)
	lambda.Start(handler)
}

func handler(ctx context.Context, req events.APIGatewayWebsocketProxyRequest) (events.APIGatewayProxyResponse, error) {
	connID := req.RequestContext.ConnectionID
	switch req.RequestContext.RouteKey {
	case "$connect":
		if token != "" && req.QueryStringParameters["token"] != token {
			return resp(401, "unauthorized"), nil
		}
		if err := putConnection(ctx, connID); err != nil {
			return resp(500, "register connection failed"), nil
		}
		return resp(200, "connected"), nil

	case "$disconnect":
		_ = deleteConnection(ctx, connID)
		return resp(200, "disconnected"), nil

	default:
		return route(ctx, req, connID)
	}
}

func route(ctx context.Context, req events.APIGatewayWebsocketProxyRequest, connID string) (events.APIGatewayProxyResponse, error) {
	var env envelope
	if err := json.Unmarshal([]byte(req.Body), &env); err != nil {
		return resp(400, "bad message"), nil
	}
	api := newCallbackClient(ctx, req)

	switch env.Type {
	case "register":
		var d registerData
		_ = json.Unmarshal(env.Data, &d)
		if token != "" && d.Token != token {
			send(ctx, api, connID, "error", map[string]string{"msg": "unauthorized"})
			return resp(200, ""), nil
		}
		if err := tagDevice(ctx, connID, d); err != nil {
			return resp(500, "register failed"), nil
		}
		send(ctx, api, connID, "registered", map[string]string{"deviceId": d.DeviceID})

	case "auth":
		// App auth: token is checked at $connect; just acknowledge.
		send(ctx, api, connID, "auth_ok", struct{}{})

	case "list":
		devs, err := listDevices(ctx)
		if err != nil {
			return resp(500, "list failed"), nil
		}
		send(ctx, api, connID, "devices", map[string]any{"devices": devs})

	case "open":
		var d openData
		_ = json.Unmarshal(env.Data, &d)
		agentConn, err := connForDevice(ctx, d.DeviceID)
		if err != nil || agentConn == "" {
			send(ctx, api, connID, "error", map[string]string{"msg": "device offline: " + d.DeviceID})
			return resp(200, ""), nil
		}
		sessionID := newID()
		// Tell the agent to open a data connection (Fargate /data via NLB).
		send(ctx, api, agentConn, "open", map[string]string{"sessionId": sessionID})
		send(ctx, api, connID, "opened", map[string]string{"sessionId": sessionID})

	default:
		send(ctx, api, connID, "error", map[string]string{"msg": "unsupported message"})
	}
	return resp(200, ""), nil
}

// --- DynamoDB registry ---

func putConnection(ctx context.Context, connID string) error {
	_, err := ddb.PutItem(ctx, &dynamodb.PutItemInput{
		TableName: aws.String(table),
		Item: map[string]ddbtypes.AttributeValue{
			"connectionId": &ddbtypes.AttributeValueMemberS{Value: connID},
			"status":       &ddbtypes.AttributeValueMemberS{Value: "connected"},
			"expiresAt":    &ddbtypes.AttributeValueMemberN{Value: fmt.Sprintf("%d", time.Now().Add(2*time.Hour).Unix())},
		},
	})
	return err
}

func tagDevice(ctx context.Context, connID string, d registerData) error {
	_, err := ddb.PutItem(ctx, &dynamodb.PutItemInput{
		TableName: aws.String(table),
		Item: map[string]ddbtypes.AttributeValue{
			"connectionId": &ddbtypes.AttributeValueMemberS{Value: connID},
			"deviceId":     &ddbtypes.AttributeValueMemberS{Value: d.DeviceID},
			"name":         &ddbtypes.AttributeValueMemberS{Value: d.Name},
			"platform":     &ddbtypes.AttributeValueMemberS{Value: d.Platform},
			"status":       &ddbtypes.AttributeValueMemberS{Value: "online"},
			"expiresAt":    &ddbtypes.AttributeValueMemberN{Value: fmt.Sprintf("%d", time.Now().Add(2*time.Hour).Unix())},
		},
	})
	return err
}

func deleteConnection(ctx context.Context, connID string) error {
	_, err := ddb.DeleteItem(ctx, &dynamodb.DeleteItemInput{
		TableName: aws.String(table),
		Key:       map[string]ddbtypes.AttributeValue{"connectionId": &ddbtypes.AttributeValueMemberS{Value: connID}},
	})
	return err
}

func listDevices(ctx context.Context) ([]deviceInfo, error) {
	out, err := ddb.Scan(ctx, &dynamodb.ScanInput{TableName: aws.String(table)})
	if err != nil {
		return nil, err
	}
	var devs []deviceInfo
	for _, item := range out.Items {
		dev, ok := item["deviceId"].(*ddbtypes.AttributeValueMemberS)
		if !ok || dev.Value == "" {
			continue // a bare app/agent connection without a device tag
		}
		di := deviceInfo{DeviceID: dev.Value, Online: true}
		if n, ok := item["name"].(*ddbtypes.AttributeValueMemberS); ok {
			di.Name = n.Value
		}
		if p, ok := item["platform"].(*ddbtypes.AttributeValueMemberS); ok {
			di.Platform = p.Value
		}
		devs = append(devs, di)
	}
	return devs, nil
}

func connForDevice(ctx context.Context, deviceID string) (string, error) {
	out, err := ddb.Query(ctx, &dynamodb.QueryInput{
		TableName:                 aws.String(table),
		IndexName:                 aws.String("by-device"),
		KeyConditionExpression:    aws.String("deviceId = :d"),
		ExpressionAttributeValues: map[string]ddbtypes.AttributeValue{":d": &ddbtypes.AttributeValueMemberS{Value: deviceID}},
		Limit:                     aws.Int32(1),
	})
	if err != nil || len(out.Items) == 0 {
		return "", err
	}
	if c, ok := out.Items[0]["connectionId"].(*ddbtypes.AttributeValueMemberS); ok {
		return c.Value, nil
	}
	return "", nil
}

// --- WebSocket callback ---

func newCallbackClient(ctx context.Context, req events.APIGatewayWebsocketProxyRequest) *apigatewaymanagementapi.Client {
	cfg, _ := awsconfig.LoadDefaultConfig(ctx)
	endpoint := fmt.Sprintf("https://%s/%s", req.RequestContext.DomainName, req.RequestContext.Stage)
	return apigatewaymanagementapi.NewFromConfig(cfg, func(o *apigatewaymanagementapi.Options) {
		o.BaseEndpoint = aws.String(endpoint)
	})
}

func send(ctx context.Context, api *apigatewaymanagementapi.Client, connID, typ string, data any) {
	raw, _ := json.Marshal(data)
	msg, _ := json.Marshal(envelope{Type: typ, Data: raw})
	_, err := api.PostToConnection(ctx, &apigatewaymanagementapi.PostToConnectionInput{
		ConnectionId: aws.String(connID),
		Data:         msg,
	})
	if err != nil {
		log.Printf("post to %s failed: %v", connID, err)
	}
}

func newID() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	return hex.EncodeToString(b[:])
}

func resp(code int, body string) events.APIGatewayProxyResponse {
	return events.APIGatewayProxyResponse{StatusCode: code, Body: body}
}
