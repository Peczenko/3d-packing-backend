package com.packing.backend.infra.conversion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssimpTest {

    @Test
    void reportsTheLastLoadFailure() {
        String output = String.join("\n",
                                    "assimp export: select file format: 'objnomtl' (Wavefront OBJ format without material file)",
                                    "Launching asset import ...           OK",
                                    "ERROR: Failed to load file: No suitable reader found for the file format of file \"source.glb\".");

        assertThat(Assimp.loadFailure(output)).contains("No suitable reader found for the file format of file \"source.glb\".");
    }

    @Test
    void reportsNothingWhenAssimpNamedNoReason() {
        assertThat(Assimp.loadFailure("Segmentation fault")).isEmpty();
        assertThat(Assimp.loadFailure("ERROR: Failed to load file: ")).isEmpty();
    }

    @Test
    void asksForAWeldedTriangulatedObjWithoutAMaterialFile() {
        assertThat(Assimp.OPTIONS).containsExactly("--format=objnomtl", "--triangulate", "--drop-normals", "--join-identical-vertices");
    }
}
