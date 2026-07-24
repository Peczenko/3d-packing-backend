package com.packing.backend.infra.storage;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;

interface BlobSasIssuer {

    String sasToken(BlobClient blob, BlobServiceSasSignatureValues values);
}
