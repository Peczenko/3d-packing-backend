package contracts

import (
	"encoding/json"
	"fmt"
	"slices"
)

var AcceptedFormats = map[string]string{
	"OBJ": "obj",
}

var ParameterFlags = map[string]func(json.RawMessage) ([]string, error){}

func ParameterArgs(parameters json.RawMessage) ([]string, error) {
	var values map[string]json.RawMessage
	if err := json.Unmarshal(parameters, &values); err != nil || values == nil {
		return nil, fmt.Errorf("%w: parameters must be a JSON object", ErrInvalidParameters)
	}
	names := make([]string, 0, len(values))
	for name := range values {
		names = append(names, name)
	}
	slices.Sort(names)

	var args []string
	for _, name := range names {
		flag, ok := ParameterFlags[name]
		if !ok {
			return nil, fmt.Errorf("%w: unknown parameter %q", ErrInvalidParameters, name)
		}
		tokens, err := flag(values[name])
		if err != nil {
			return nil, fmt.Errorf("%w: parameter %q: %w", ErrInvalidParameters, name, err)
		}
		args = append(args, tokens...)
	}
	return args, nil
}
