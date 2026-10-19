package contracts

import (
	"encoding/json"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"testing"
)

const inputSchemaDir = "../../../contracts/packing-input"

func newestInputSchema(t *testing.T) map[string]any {
	t.Helper()
	versions := discoverVersions(t, inputSchemaDir)
	newest := versions[len(versions)-1]
	data, err := os.ReadFile(filepath.Join(inputSchemaDir, "v"+strconv.Itoa(newest), "job-input.schema.json"))
	if err != nil {
		t.Fatalf("read input schema v%d: %v", newest, err)
	}
	var schema map[string]any
	if err := json.Unmarshal(data, &schema); err != nil {
		t.Fatalf("parse input schema v%d: %v", newest, err)
	}
	return schema
}

func dig(t *testing.T, node any, path ...string) any {
	t.Helper()
	for _, key := range path {
		object, ok := node.(map[string]any)
		if !ok {
			t.Fatalf("input schema has no object at %v", path)
		}
		node = object[key]
	}
	return node
}

func sortedKeys[V any](m map[string]V) []string {
	keys := make([]string, 0, len(m))
	for key := range m {
		keys = append(keys, key)
	}
	slices.Sort(keys)
	return keys
}

func TestParameterFlagsCoverExactlyTheSchemaParameters(t *testing.T) {
	declared, _ := dig(t, newestInputSchema(t), "properties", "parameters", "properties").(map[string]any)

	if got, want := sortedKeys(ParameterFlags), sortedKeys(declared); !slices.Equal(got, want) {
		t.Fatalf("worker maps parameters %q, the input schema declares %q", got, want)
	}
}

func TestAcceptedFormatsAreTheSchemaAcceptedFormats(t *testing.T) {
	raw, _ := dig(t, newestInputSchema(t), "properties", "items", "x-acceptedFormats").([]any)
	declared := make([]string, 0, len(raw))
	for _, format := range raw {
		declared = append(declared, format.(string))
	}
	slices.Sort(declared)

	if got := sortedKeys(AcceptedFormats); !slices.Equal(got, declared) {
		t.Fatalf("worker accepts %q, the input schema declares %q", got, declared)
	}
}

func TestLimitsMatchTheSchema(t *testing.T) {
	schema := newestInputSchema(t)
	for _, tc := range []struct {
		path []string
		want float64
	}{
		{[]string{"properties", "items", "maxItems"}, MaxItems},
		{[]string{"properties", "items", "x-maxTotalCopies"}, MaxTotalCopies},
		{[]string{"properties", "items", "items", "properties", "copies", "minimum"}, MinCopies},
		{[]string{"properties", "items", "items", "properties", "copies", "maximum"}, MaxCopies},
		{[]string{"properties", "maxRuntimeSeconds", "minimum"}, MinRuntimeSeconds},
		{[]string{"properties", "maxRuntimeSeconds", "maximum"}, MaxRuntimeSeconds},
	} {
		if got, _ := dig(t, schema, tc.path...).(float64); got != tc.want {
			t.Fatalf("%v = %v in the schema, the worker enforces %v", tc.path, got, tc.want)
		}
	}
}
