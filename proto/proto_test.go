package proto

import (
	"encoding/json"
	"reflect"
	"testing"
)

// TestEncodeDecodeRoundTrip verifies every control message survives an
// Encode -> Decode -> DecodeData round trip with identical contents.
func TestEncodeDecodeRoundTrip(t *testing.T) {
	cases := []struct {
		name    string
		typ     Type
		payload any
		into    func() any
	}{
		{"register", TypeRegister, &Register{DeviceID: "dev1", Name: "ec2-box", Token: "tok", Platform: "linux"}, func() any { return &Register{} }},
		{"registered", TypeRegistered, &Registered{DeviceID: "dev1"}, func() any { return &Registered{} }},
		{"auth", TypeAuth, &Auth{Token: "tok"}, func() any { return &Auth{} }},
		{"auth_ok", TypeAuthOK, &AuthOK{}, func() any { return &AuthOK{} }},
		{"list", TypeList, &List{}, func() any { return &List{} }},
		{"devices", TypeDevices, &Devices{Devices: []DeviceInfo{
			{DeviceID: "dev1", Name: "ec2-box", Platform: "linux", Online: true},
			{DeviceID: "dev2", Name: "mac", Platform: "darwin", Online: false},
		}}, func() any { return &Devices{} }},
		{"open_app", TypeOpen, &Open{DeviceID: "dev1"}, func() any { return &Open{} }},
		{"open_agent", TypeOpen, &Open{SessionID: "sess-abc"}, func() any { return &Open{} }},
		{"opened", TypeOpened, &Opened{SessionID: "sess-abc"}, func() any { return &Opened{} }},
		{"error", TypeError, &Error{Msg: "bad token"}, func() any { return &Error{} }},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			raw, err := Encode(c.typ, c.payload)
			if err != nil {
				t.Fatalf("Encode: %v", err)
			}
			gotType, data, err := Decode(raw)
			if err != nil {
				t.Fatalf("Decode: %v", err)
			}
			if gotType != c.typ {
				t.Fatalf("type = %q, want %q", gotType, c.typ)
			}
			out := c.into()
			if err := DecodeData(data, out); err != nil {
				t.Fatalf("DecodeData: %v", err)
			}
			if !reflect.DeepEqual(out, c.payload) {
				t.Fatalf("round trip mismatch:\n got  %+v\n want %+v", out, c.payload)
			}
		})
	}
}

// TestDecodeErrors verifies Decode rejects empty and malformed input.
func TestDecodeErrors(t *testing.T) {
	if _, _, err := Decode(nil); err != ErrEmpty {
		t.Fatalf("Decode(nil) err = %v, want ErrEmpty", err)
	}
	if _, _, err := Decode([]byte("not json")); err == nil {
		t.Fatal("Decode(garbage) = nil error, want error")
	}
	if _, _, err := Decode([]byte(`{"data":{}}`)); err == nil {
		t.Fatal("Decode(no type) = nil error, want error")
	}
}

// TestEncodeNilPayload verifies a nil payload yields an envelope with no data.
func TestEncodeNilPayload(t *testing.T) {
	raw, err := Encode(TypePing, nil)
	if err != nil {
		t.Fatalf("Encode: %v", err)
	}
	var env Envelope
	if err := json.Unmarshal(raw, &env); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if env.Type != TypePing {
		t.Fatalf("type = %q, want %q", env.Type, TypePing)
	}
	if len(env.Data) != 0 {
		t.Fatalf("data = %q, want empty", env.Data)
	}
}
