# Packing job input

`v<N>/job-input.schema.json` is the JSON Schema (2020-12) every
`POST /api/v1/projects/{projectId}/packing-jobs` body is validated against. The API serves the
newest version at `GET /api/v1/packing/input-schema`; the frontend renders the job form from it.

## Annotation keywords

Validators ignore these; the frontend and, where stated, the platform read them.

| Keyword | On | Meaning |
|---|---|---|
| `x-widget: "project-files"` | `items` | Render the project-file picker instead of a generic array editor. The only widget value. |
| `x-acceptedFormats` | `items` | `ModelFormat` names the pinned engine reads. The API rejects other formats; the worker's `contracts.AcceptedFormats` is pinned to it by a test. |
| `x-maxTotalCopies` | `items` | The total-copies cap JSON Schema cannot express. Enforced by `PackingInput`. |
| `x-unit` | any number | Unit label to display. |

## Versioning

Within a version, only changes that keep every stored input of that version valid: adding an
optional property **with a `default`**, relaxing a limit, adding an accepted format. Anything else
— removing or renaming a property, tightening a limit, changing a type, dropping a format — is a
new directory `v<N+1>`, and `JsonSchemaPackingInputSchema.CURRENT_VERSION` moves with it.

Every property under `parameters` must declare a `default`, and must have an entry in the worker's
`contracts.ParameterFlags`; both are enforced by tests.
