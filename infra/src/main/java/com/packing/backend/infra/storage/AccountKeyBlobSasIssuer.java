package com.packing.backend.infra.storage;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;

/**
 * Not usable in Azure: the application authenticates with a managed identity there, and no
 * account key is present in the process.
 */
class AccountKeyBlobSasIssuer implements BlobSasIssuer {

    @Override
    public String sasToken(BlobClient blob, BlobServiceSasSignatureValues values) {
        return blob.generateSas(values);
    }
}
