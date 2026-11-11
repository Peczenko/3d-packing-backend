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

func TestEngineParametersBecomePackerFlags(t *testing.T) {
	args, err := ParameterArgs(json.RawMessage(`{"container":{"width":0.4,"length":200,"height":1e21},"gap":0.002,"algorithm":"constructive","seed":4294967295.0}`))
	if err != nil {
		t.Fatalf("ParameterArgs: %v", err)
	}
	want := []string{
		"--algorithm", "constructive",
		"--width", "0.4", "--length", "200", "--height", "1e+21",
		"--gap", "0.002",
		"--seed", "4294967295",
	}
	if !slices.Equal(args, want) {
		t.Fatalf("args = %q, want %q", args, want)
	}
}

func TestEngineParametersRejectWhatThePackerWouldRefuse(t *testing.T) {
	for _, parameters := range []string{
		`{"container":{"width":1,"length":1}}`,
		`{"container":{"width":1,"length":1,"height":0}}`,
		`{"container":{"width":1,"length":1,"height":1,"depth":1}}`,
		`{"container":null}`,
		`{"gap":-1}`,
		`{"gap":null}`,
		`{"gap":"1"}`,
		`{"algorithm":"greedy"}`,
		`{"seed":-1}`,
		`{"seed":1.5}`,
		`{"seed":4294967296}`,
	} {
		if _, err := ParameterArgs(json.RawMessage(parameters)); !errors.Is(err, ErrInvalidParameters) {
			t.Fatalf("ParameterArgs(%s) error = %v, want ErrInvalidParameters", parameters, err)
		}
	}
}
