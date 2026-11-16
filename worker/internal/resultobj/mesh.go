package resultobj

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"io"
	"math"
	"strconv"
)

type mesh struct {
	vertices []float64
	faces    []uint32
}

type malformedError struct {
	line    int64
	problem string
}

func (e *malformedError) Error() string {
	if e.line == 0 {
		return e.problem
	}
	return fmt.Sprintf("line %d: %s", e.line, e.problem)
}

type meshReader struct {
	raw     []float64
	corners []int32
	sizes   []uint32
	line    int64
	tokens  [][]byte
}

func readMesh(r io.Reader) (*mesh, error) {
	reader := &meshReader{}
	in := bufio.NewReaderSize(r, 1<<20)
	var long []byte
	for {
		chunk, err := in.ReadSlice('\n')
		if errors.Is(err, bufio.ErrBufferFull) {
			long = append(long, chunk...)
			continue
		}
		line := chunk
		if len(long) > 0 {
			line = append(long, chunk...)
			long = long[:0]
		}
		if len(line) > 0 {
			if parseErr := reader.parseLine(line); parseErr != nil {
				return nil, parseErr
			}
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, err
		}
	}
	return reader.build()
}

func isBlank(c byte) bool {
	return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\v' || c == '\f'
}

func (r *meshReader) parseLine(line []byte) error {
	r.line++
	line = bytes.TrimLeft(line, " \t")
	if len(line) < 2 || !isBlank(line[1]) {
		return nil
	}
	switch line[0] {
	case 'v':
		return r.vertex(line[1:])
	case 'f':
		return r.face(line[1:])
	}
	return nil
}

func (r *meshReader) split(rest []byte) [][]byte {
	r.tokens = r.tokens[:0]
	for len(rest) > 0 {
		start := 0
		for start < len(rest) && isBlank(rest[start]) {
			start++
		}
		end := start
		for end < len(rest) && !isBlank(rest[end]) {
			end++
		}
		if end > start {
			if rest[start] == '#' {
				break
			}
			r.tokens = append(r.tokens, rest[start:end])
		}
		rest = rest[end:]
	}
	return r.tokens
}

func (r *meshReader) vertex(rest []byte) error {
	tokens := r.split(rest)
	for axis := range 3 {
		value := 0.0
		if axis < len(tokens) {
			parsed, err := strconv.ParseFloat(string(tokens[axis]), 64)
			if err != nil {
				return &malformedError{r.line, fmt.Sprintf("%q is not a coordinate", tokens[axis])}
			}
			value = parsed
		}
		if math.IsInf(value, 0) || math.IsNaN(value) {
			return &malformedError{r.line, "a coordinate is not finite"}
		}
		r.raw = append(r.raw, value)
	}
	return nil
}

func (r *meshReader) face(rest []byte) error {
	tokens := r.split(rest)
	if len(tokens) < 3 {
		return nil
	}
	declared := int64(len(r.raw) / 3)
	for _, token := range tokens {
		if slash := bytes.IndexByte(token, '/'); slash >= 0 {
			token = token[:slash]
		}
		index, err := strconv.ParseInt(string(token), 10, 32)
		if err != nil || index == 0 {
			return &malformedError{r.line, fmt.Sprintf("%q is not a vertex reference", token)}
		}
		if index < 0 {
			index += declared
		} else {
			index--
		}
		if index < 0 {
			return &malformedError{r.line, "a face refers to a vertex before the first one"}
		}
		r.corners = append(r.corners, int32(index))
	}
	r.sizes = append(r.sizes, uint32(len(tokens)))
	return nil
}

func (r *meshReader) build() (*mesh, error) {
	count := int32(len(r.raw) / 3)
	for _, corner := range r.corners {
		if corner >= count {
			return nil, &malformedError{0, fmt.Sprintf("a face refers to vertex %d, but the model declares %d", corner+1, count)}
		}
	}
	triangles := make([]int32, 0, len(r.corners))
	at := 0
	for _, size := range r.sizes {
		polygon := r.corners[at : at+int(size)]
		at += int(size)
		switch size {
		case 3:
			triangles = append(triangles, polygon...)
		case 4:
			if r.distance(polygon[0], polygon[2]) < r.distance(polygon[1], polygon[3]) {
				triangles = append(triangles, polygon[0], polygon[1], polygon[2], polygon[0], polygon[2], polygon[3])
			} else {
				triangles = append(triangles, polygon[0], polygon[1], polygon[3], polygon[1], polygon[2], polygon[3])
			}
		default:
			for i := 1; i+1 < len(polygon); i++ {
				triangles = append(triangles, polygon[0], polygon[i], polygon[i+1])
			}
		}
	}
	if len(triangles) == 0 {
		return nil, &malformedError{0, "the model has no faces"}
	}

	remap := make([]int32, count)
	for i := range remap {
		remap[i] = -1
	}
	result := &mesh{faces: make([]uint32, len(triangles))}
	for i, corner := range triangles {
		if remap[corner] < 0 {
			remap[corner] = int32(len(result.vertices) / 3)
			result.vertices = append(result.vertices, r.raw[3*corner], r.raw[3*corner+1], r.raw[3*corner+2])
		}
		result.faces[i] = uint32(remap[corner])
	}
	return result, nil
}

func (r *meshReader) distance(a, b int32) float64 {
	dx := r.raw[3*b] - r.raw[3*a]
	dy := r.raw[3*b+1] - r.raw[3*a+1]
	dz := r.raw[3*b+2] - r.raw[3*a+2]
	return dx*dx + dy*dy + dz*dz
}
