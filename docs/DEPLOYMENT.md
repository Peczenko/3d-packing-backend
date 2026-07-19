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

## 5. Runtime configuration: Postgres via Azure Key Vault

The `azure` Spring profile (`application-azure.properties`) reads the Postgres endpoint,
username, and password from Azure Key Vault at `https://packing-kv.vault.azure.net/`,
authenticating with the container app's **system-assigned managed identity** (no client
secret, consistent with the OIDC-only approach used for CD).

Spring Cloud Azure's Key Vault property source maps dots to dashes, so property
`db.endpoint` reads secret `db-endpoint`, etc. — this is why the secrets below use that
naming.

### 5a. Store the secrets

```bash
VAULT=packing-kv

az keyvault secret set --vault-name "$VAULT" --name db-endpoint --value "<postgres-server-fqdn>"
az keyvault secret set --vault-name "$VAULT" --name db-username --value "<db-username>"
az keyvault secret set --vault-name "$VAULT" --name db-password --value "<db-password>"
```

### 5b. Grant the container app access to the vault

```bash
# Enable a system-assigned managed identity on the container app
az containerapp identity assign --name "$APP" --resource-group "$RG" --system-assigned
PRINCIPAL_ID=$(az containerapp identity show --name "$APP" --resource-group "$RG" --query principalId -o tsv)

VAULT_ID=$(az keyvault show --name "$VAULT" --query id -o tsv)

# If the vault uses RBAC authorization (recommended):
az role assignment create --assignee "$PRINCIPAL_ID" --role "Key Vault Secrets User" --scope "$VAULT_ID"

# If the vault instead uses classic access policies:
az keyvault set-policy --name "$VAULT" --object-id "$PRINCIPAL_ID" --secret-permissions get list
```

### 5c. Activate the profile

```bash
az containerapp update --name "$APP" --resource-group "$RG" \
  --set-env-vars SPRING_PROFILES_ACTIVE=azure
```

`DB_PORT` / `DB_NAME` default to `5432` / `packing` and only need overriding via
`--set-env-vars` if the server differs from that.

---

## 6. Runtime configuration: Firebase via Azure Key Vault

Firebase Authentication owns user identity. The backend needs two things from it:

| What | Why | Secret |
|------|-----|--------|
| Project id | Derives the token issuer (`https://securetoken.google.com/<project-id>`) and the expected `aud` claim. Required even without the Admin SDK. | `firebase-project-id` |
| Service-account JSON | Lets the Admin SDK set custom claims (roles), revoke refresh tokens and delete users. | `firebase-service-account` |

Verifying ID tokens needs **only** the project id — the signing keys are public. The
service account is required solely for the operations that *change* Firebase-side state.

### 6a. Store the secrets

Get the service-account JSON from the Firebase console:
**Project settings → Service accounts → Generate new private key**.

```bash
VAULT=packing-kv

az keyvault secret set --vault-name "$VAULT" --name firebase-project-id \
  --value "<firebase-project-id>"

# Base64-encoded, so no shell quoting or line-ending handling can mangle the PEM
# private key embedded in the JSON. Raw JSON is also accepted by the application.
az keyvault secret set --vault-name "$VAULT" --name firebase-service-account \
  --value "$(base64 -w0 firebase-service-account.json)"
```

> The container app's managed identity already has vault access from step 5b — no
> additional role assignment is needed.

The `azure` profile reads these via placeholder indirection
(`firebase.project-id=${firebase.project.id}`), because Spring Cloud Azure resolves a
requested property by swapping `.` for `-`. This is the same
`Environment.getProperty()` path the `db.*` settings use; see the comments in
`application-azure.properties` for why binding `firebase.project-id` directly would not
work.

### 6b. How roles actually work

The backend **does not trust the `roles` custom claim** for authorization. That claim is a
snapshot taken when the token was minted, and Firebase's revocation API only invalidates
*refresh* tokens — an ID token already in the client's hands keeps working for up to an
hour. Authorization therefore reads `users.role` from PostgreSQL on every request.

The claim is still written (after the database commits) so a frontend can render admin UI
without an extra round trip. Treat it as advisory: if it disagrees with the database, the
database wins.

Two consequences worth knowing operationally:

- A role change takes effect on the **next request**, not on the next token refresh.
- Deleting an account anonymises the row and keeps a tombstone rather than removing it, so
  a token issued just before the deletion is rejected immediately instead of silently
  re-provisioning a fresh profile. Only the opaque `firebase_uid` is retained.

### 6c. Running without the Admin SDK

Set `firebase.admin-enabled=false` to skip Admin SDK initialisation entirely. Token
verification keeps working; only Firebase-side mutations (role assignment, account
deletion) become unavailable and fail loudly with a 503. This is what CI uses, since no
credentials exist there.

### 6d. Local development

`docker-compose.yml` provides the local stack. Copy `.env.example` to `.env` first (it is
gitignored) and set at least `FIREBASE_PROJECT_ID`.

**The usual loop — database in Docker, app from Gradle.** Faster restarts, a debugger, and
no image rebuild per change:

```bash
docker compose up -d          # postgres only; `app` is behind a Compose profile

export FIREBASE_PROJECT_ID=<your-dev-firebase-project>
export FIREBASE_ADMIN_ENABLED=false                                                # optional
export FIREBASE_SERVICE_ACCOUNT_B64="$(base64 -w0 firebase-service-account.json)"  # optional
./gradlew :app:bootRun --args='--spring.profiles.active=local'
```

Note the Gradle path does **not** read `.env` — export the variables in your shell.

**The whole thing in Docker**, when you want to exercise the real container image:

```bash
docker compose --profile app up --build     # reads .env
```

Either way: `docker compose down` to stop, `docker compose down -v` to also drop the
database volume and start from an empty schema.

Without `FIREBASE_SERVICE_ACCOUNT_B64` the Admin SDK falls back to Application Default
Credentials (`gcloud auth application-default login` or
`GOOGLE_APPLICATION_CREDENTIALS`). If neither is available, set
`FIREBASE_ADMIN_ENABLED=false`.

> Every setting in `application-local.properties` is read through an explicit
> `${ENV_VAR}` placeholder rather than Spring's environment-variable binding. That is
> deliberate: relaxed binding uppercases and **drops hyphens**, so `firebase.project-id`
> would have to be set as `FIREBASE_PROJECTID`, while the intuitive `FIREBASE_PROJECT_ID`
> maps to `firebase.project.id` and silently fails to bind. Naming the variable in the
> properties file removes the trap.

To call the API manually, sign in through a Firebase client for the same project and pass
the resulting ID token:

```bash
curl -H "Authorization: Bearer <firebase-id-token>" http://localhost:8080/api/v1/users/me
```

The first such call provisions the local user row (just-in-time provisioning); subsequent
calls return the same profile.

### 6e. Other runtime settings (future work)

The storage account still needs to be wired the same way as the backend grows.
