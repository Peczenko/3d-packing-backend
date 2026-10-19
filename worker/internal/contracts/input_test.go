package contracts

import (
	"encoding/json"
	"errors"
	"slices"
	"testing"
)

func withParameterFlags(t *testing.T, flags map[string]func(json.RawMessage) ([]string, error)) {
	t.Helper()
	saved := ParameterFlags
	ParameterFlags = flags
	t.Cleanup(func() { ParameterFlags = saved })
}

func TestParameterArgsTranslatesEveryParameterInNameOrder(t *testing.T) {
	withParameterFlags(t, map[string]func(json.RawMessage) ([]string, error){
		"seed":  func(v json.RawMessage) ([]string, error) { return []string{"-s", string(v)}, nil },
		"angle": func(v json.RawMessage) ([]string, error) { return []string{"-a", string(v)}, nil },
	})

	args, err := ParameterArgs(json.RawMessage(`{"seed":7,"angle":90}`))
	if err != nil {
		t.Fatalf("ParameterArgs: %v", err)
	}
	if want := []string{"-a", "90", "-s", "7"}; !slices.Equal(args, want) {
		t.Fatalf("args = %q, want %q", args, want)
	}
}

func TestParameterArgsReportsAFlagThatRejectsItsValue(t *testing.T) {
	withParameterFlags(t, map[string]func(json.RawMessage) ([]string, error){
		"seed": func(json.RawMessage) ([]string, error) { return nil, errors.New("not an integer") },
	})

	if _, err := ParameterArgs(json.RawMessage(`{"seed":"x"}`)); !errors.Is(err, ErrInvalidParameters) {
		t.Fatalf("expected ErrInvalidParameters, got %v", err)
	}
}

func TestParameterArgsAcceptsNoParameters(t *testing.T) {
	args, err := ParameterArgs(json.RawMessage(`{}`))
	if err != nil || len(args) != 0 {
		t.Fatalf("ParameterArgs({}) = (%q, %v), want (none, nil)", args, err)
	}
}
