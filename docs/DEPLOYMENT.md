# Deployment & Release Guide

This document describes the branching model, the release process, and how to wire up
deployment to **Azure Container Apps**. The pipelines are already committed; deployment
"activates" once you provision Azure and add the GitHub secrets/variables below.

---

## 1. Branching & release model

| Branch    | Purpose                                                                 |
|-----------|-------------------------------------------------------------------------|
| `dev`     | Integration branch (default). Feature branches are PR'd here.            |
| `master`  | Release branch. Merging `dev → master` cuts a release.                   |

Flow:

1. Branch off `dev`, open a PR back into `dev` → **CI** (`ci.yml`) runs `./gradlew build`.
2. When ready to release, bump `version` in `build.gradle` (e.g. `0.0.1-SNAPSHOT` →
   `0.0.2-SNAPSHOT`) on `dev`, then open a PR `dev → master`.
3. On merge to `master`:
   - **`release.yml`** creates tag `vX.Y.Z` + a GitHub Release (notes auto-generated).
     The `-SNAPSHOT` suffix is stripped, so `0.0.2-SNAPSHOT` releases as `v0.0.2`.
     Re-runs at the same version are skipped, so bump the version to release again.
   - **`cd.yml`** builds the Docker image, pushes it to GHCR, and (if enabled) deploys.

---

## 2. Container image (GHCR)

CD publishes `ghcr.io/peczenko/3d-packing-backend` tagged with the release version and
`latest`, using the built-in `GITHUB_TOKEN` — no extra credentials needed to publish.

To let Azure Container Apps pull it **without** credentials, make the package public:
GitHub → repo **Packages** → `3d-packing-backend` → **Package settings** → **Change
visibility** → Public. (Alternatively keep it private and attach a GitHub PAT to the
container app — see step 4.)

---

## 3. One-time Azure provisioning

Prerequisites: [Azure CLI](https://learn.microsoft.com/cli/azure/) installed, and
`az login` completed as yourself. Because your tenant blocks app registrations in the
portal UI, everything below is done via the CLI (which is permitted).

```bash
# --- Variables (edit these) -------------------------------------------------
RG=rg-3d-packing
LOCATION=westeurope
ENV=cae-3d-packing                 # Container Apps environment
APP=3d-packing-backend             # Container app name
IMAGE=ghcr.io/peczenko/3d-packing-backend:latest
GH_REPO=Peczenko/3d-packing-backend

SUB_ID=$(az account show --query id -o tsv)
TENANT_ID=$(az account show --query tenantId -o tsv)

# --- Core resources ---------------------------------------------------------
az group create --name "$RG" --location "$LOCATION"

az extension add --name containerapp --upgrade
az provider register --namespace Microsoft.App --wait
az provider register --namespace Microsoft.OperationalInsights --wait

az containerapp env create \
  --name "$ENV" --resource-group "$RG" --location "$LOCATION"

# Create the app (public image works out of the box; swap IMAGE once GHCR is public)
az containerapp create \
  --name "$APP" --resource-group "$RG" --environment "$ENV" \
  --image "$IMAGE" \
  --target-port 8080 --ingress external \
  --min-replicas 0 --max-replicas 1
```

### 3a. App registration + GitHub OIDC (no client secret)

```bash
APP_ID=$(az ad app create --display-name "gh-oidc-3d-packing-backend" --query appId -o tsv)
az ad sp create --id "$APP_ID"

# Federated credential: GitHub Actions on the master branch may request tokens
az ad app federated-credential create --id "$APP_ID" --parameters "{
  \"name\": \"github-master\",
  \"issuer\": \"https://token.actions.githubusercontent.com\",
  \"subject\": \"repo:${GH_REPO}:ref:refs/heads/master\",
  \"audiences\": [\"api://AzureADTokenExchange\"]
}"

# Let the identity manage the container app (scope to the RG)
az role assignment create \
  --assignee "$APP_ID" --role Contributor \
  --scope "/subscriptions/${SUB_ID}/resourceGroups/${RG}"

echo "AZURE_CLIENT_ID=$APP_ID"
echo "AZURE_TENANT_ID=$TENANT_ID"
echo "AZURE_SUBSCRIPTION_ID=$SUB_ID"
```

> If you also deploy via `workflow_dispatch` from other branches, add another federated
> credential with subject `repo:${GH_REPO}:ref:refs/heads/dev` (or an environment subject).

> The `az role assignment create` step needs **Owner** or **User Access Administrator** on
> the resource group. If you only have Contributor, ask a subscription admin to run just
> that one command with the printed `APP_ID`.

### 3b. (Optional) private GHCR pull

Only if you keep the GHCR package private:

```bash
az containerapp registry set \
  --name "$APP" --resource-group "$RG" \
  --server ghcr.io \
  --username <your-github-username> \
  --password <github-PAT-with-read:packages>
```

---

## 4. GitHub configuration

Add these under repo **Settings → Secrets and variables → Actions**, or via the CLI:

```bash
# Secrets (sensitive identifiers)
gh secret set AZURE_CLIENT_ID        --body "<APP_ID>"        --repo "$GH_REPO"
gh secret set AZURE_TENANT_ID        --body "<TENANT_ID>"     --repo "$GH_REPO"
gh secret set AZURE_SUBSCRIPTION_ID  --body "<SUB_ID>"        --repo "$GH_REPO"

# Variables (non-sensitive targets + the deploy switch)
gh variable set AZURE_RESOURCE_GROUP   --body "rg-3d-packing"      --repo "$GH_REPO"
gh variable set AZURE_CONTAINERAPP_NAME --body "3d-packing-backend" --repo "$GH_REPO"
gh variable set DEPLOY_ENABLED         --body "true"               --repo "$GH_REPO"
```

Once `DEPLOY_ENABLED=true`, the next push to `master` (or a manual `workflow_dispatch` on
**CD**) will run `az containerapp update` with the freshly built image.

---

## 5. Runtime configuration (future work)

The container app still needs its own runtime settings as the backend grows — the
PostgreSQL connection, Azure Key Vault endpoint, OAuth2 issuer, storage account, etc.
Add these as container-app environment variables / secrets, e.g.:

```bash
az containerapp update --name "$APP" --resource-group "$RG" \
  --set-env-vars SPRING_DATASOURCE_URL=... SPRING_CLOUD_AZURE_KEYVAULT_SECRET_ENDPOINT=...
```
