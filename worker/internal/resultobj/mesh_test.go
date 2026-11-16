package resultobj

import (
	"errors"
	"slices"
	"strings"
	"testing"
)

func parse(t *testing.T, obj string) *mesh {
	t.Helper()
	m, err := readMesh(strings.NewReader(obj))
	if err != nil {
		t.Fatalf("readMesh: %v", err)
	}
	return m
}

func assertMesh(t *testing.T, m *mesh, vertices []float64, faces []uint32) {
	t.Helper()
	if !slices.Equal(m.vertices, vertices) {
		t.Errorf("vertices = %v, want %v", m.vertices, vertices)
	}
	if !slices.Equal(m.faces, faces) {
		t.Errorf("faces = %v, want %v", m.faces, faces)
	}
}

func TestReadMeshKeepsOnlyReferencedVerticesInFirstUseOrder(t *testing.T) {
	m := parse(t, "v 0 0 0\nv 9 9 9\nv 1 0 0\nv 0 1 0\nf 4 1 3\n")

	assertMesh(t, m, []float64{0, 1, 0, 0, 0, 0, 1, 0, 0}, []uint32{0, 1, 2})
}

func TestReadMeshSplitsAQuadAlongItsShorterDiagonal(t *testing.T) {
	square := "v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\n"
	m := parse(t, square+"f 1 2 3 4\n")
	assertMesh(t, m, []float64{0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0}, []uint32{0, 1, 2, 1, 3, 2})

	kite := "v 0 0 0\nv 3 -1 0\nv 4 0 0\nv 3 1 0\n"
	m = parse(t, kite+"f 1 2 3 4\n")
	assertMesh(t, m, []float64{0, 0, 0, 3, -1, 0, 3, 1, 0, 4, 0, 0}, []uint32{0, 1, 2, 1, 3, 2})

	m = parse(t, kite+"f 2 3 4 1\n")
	assertMesh(t, m, []float64{3, -1, 0, 4, 0, 0, 3, 1, 0, 0, 0, 0}, []uint32{0, 1, 2, 0, 2, 3})
}

func TestReadMeshFansLargerPolygons(t *testing.T) {
	m := parse(t, "v 0 0 0\nv 2 0 0\nv 3 1 0\nv 1 2 0\nv -1 1 0\nf 1 2 3 4 5\n")

	assertMesh(t, m, []float64{0, 0, 0, 2, 0, 0, 3, 1, 0, 1, 2, 0, -1, 1, 0}, []uint32{0, 1, 2, 0, 2, 3, 0, 3, 4})
}

func TestReadMeshResolvesEveryReferenceForm(t *testing.T) {
	m := parse(t, "o thing\r\nv 0 0 0\r\nvt 0 0\r\nvn 0 0 1\r\n\tv\t1 0 0 0.5 0.5 0.5\r\nf -2/1/1 2//1 3/1\r\nv 0 1 0\r\ns off\r\nusemtl red\r\nf 3 1 2 # trailing comment\r\n")

	assertMesh(t, m, []float64{0, 0, 0, 1, 0, 0, 0, 1, 0}, []uint32{0, 1, 2, 2, 0, 1})
}

func TestReadMeshReadsMissingCoordinatesAsZeroLikePacker(t *testing.T) {
	m := parse(t, "v 1 2\nv 1\nv 0 0 3\nf 1 2 3\n")

	assertMesh(t, m, []float64{1, 2, 0, 1, 0, 0, 0, 0, 3}, []uint32{0, 1, 2})
}

func TestReadMeshSkipsFacesWithFewerThanThreeCorners(t *testing.T) {
	m := parse(t, "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2\nf 1 2 3\nl 1 2\n")

	assertMesh(t, m, []float64{0, 0, 0, 1, 0, 0, 0, 1, 0}, []uint32{0, 1, 2})
}

func TestReadMeshReadsLinesLongerThanItsBuffer(t *testing.T) {
	m := parse(t, "# "+strings.Repeat("x", 3<<20)+"\nv 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3")

	assertMesh(t, m, []float64{0, 0, 0, 1, 0, 0, 0, 1, 0}, []uint32{0, 1, 2})
}

func TestReadMeshRefusesWhatItCannotPlace(t *testing.T) {
	for _, tc := range []struct {
		name string
		obj  string
		want string
	}{
		{"an unparsable coordinate", "v 0 0 0\nv 1 x 0\n", `line 2: "x" is not a coordinate`},
		{"an infinite coordinate", "v 0 0 inf\n", "line 1: a coordinate is not finite"},
		{"vertex zero", "v 0 0 0\nf 0 1 1\n", `line 2: "0" is not a vertex reference`},
		{"a reference that is not a number", "v 0 0 0\nf a 1 1\n", `line 2: "a" is not a vertex reference`},
		{"a relative reference before the first vertex", "v 0 0 0\nf -2 1 1\n", "line 2: a face refers to a vertex before the first one"},
		{"a reference past the last vertex", "v 0 0 0\nv 1 0 0\nf 1 2 3\n", "a face refers to vertex 3, but the model declares 2"},
		{"no faces", "v 0 0 0\n", "the model has no faces"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, err := readMesh(strings.NewReader(tc.obj))
			var malformed *malformedError
			if !errors.As(err, &malformed) {
				t.Fatalf("readMesh error = %v, want a malformed model", err)
			}
			if err.Error() != tc.want {
				t.Fatalf("readMesh error = %q, want %q", err, tc.want)
			}
		})
	}
}
