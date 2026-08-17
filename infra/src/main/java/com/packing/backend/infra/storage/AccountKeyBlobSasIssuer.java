package com.packing.backend.infra.storage;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;

class AccountKeyBlobSasIssuer implements BlobSasIssuer {

    @Override
    public String sasToken(BlobClient blob, BlobServiceSasSignatureValues values) {
        return blob.generateSas(values);
    }
}
