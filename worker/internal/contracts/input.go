package contracts

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"slices"
	"strconv"
)

var AcceptedFormats = map[string]string{
	"OBJ": "obj",
}

const maxSeed = math.MaxUint32

var algorithms = []string{"anneal", "constructive"}

var ParameterFlags = map[string]func(json.RawMessage) ([]string, error){
	"algorithm": algorithmFlag,
	"container": containerFlags,
	"gap":       gapFlag,
	"seed":      seedFlag,
}

func containerFlags(raw json.RawMessage) ([]string, error) {
	var size struct {
		Width  *float64 `json:"width"`
		Length *float64 `json:"length"`
		Height *float64 `json:"height"`
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&size); err != nil {
		return nil, fmt.Errorf("must be an object of width, length and height: %w", err)
	}
	var args []string
	for _, dimension := range []struct {
		name  string
		value *float64
	}{{"width", size.Width}, {"length", size.Length}, {"height", size.Height}} {
		if dimension.value == nil || !(*dimension.value > 0) {
			return nil, fmt.Errorf("%s must be a positive number", dimension.name)
		}
		args = append(args, "--"+dimension.name, formatNumber(*dimension.value))
	}
	return args, nil
}

func gapFlag(raw json.RawMessage) ([]string, error) {
	var gap *float64
	if err := json.Unmarshal(raw, &gap); err != nil || gap == nil || *gap < 0 {
		return nil, errors.New("must be a non-negative number")
	}
	return []string{"--gap", formatNumber(*gap)}, nil
}

func algorithmFlag(raw json.RawMessage) ([]string, error) {
	var algorithm string
	if err := json.Unmarshal(raw, &algorithm); err != nil || !slices.Contains(algorithms, algorithm) {
		return nil, fmt.Errorf("must be one of %q", algorithms)
	}
	return []string{"--algorithm", algorithm}, nil
}

func seedFlag(raw json.RawMessage) ([]string, error) {
	var seed *float64
	if err := json.Unmarshal(raw, &seed); err != nil || seed == nil || *seed < 0 || *seed > maxSeed || *seed != math.Trunc(*seed) {
		return nil, fmt.Errorf("must be an integer between 0 and %d", uint64(maxSeed))
	}
	return []string{"--seed", strconv.FormatUint(uint64(*seed), 10)}, nil
}

func formatNumber(value float64) string {
	return strconv.FormatFloat(value, 'g', -1, 64)
}

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
