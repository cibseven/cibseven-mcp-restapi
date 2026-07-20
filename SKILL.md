---
name: multipart-binary-upload
description: Use when calling any CIB seven MCP tool that uploads a binary part via a "data" or "content" field (multipart/form-data) — e.g. createDeployment, addAttachment, setBinaryTaskVariable, setProcessInstanceVariableBinary, setLocalExecutionVariableBinary. Ensures a "filename" argument with the correct name and extension is always sent alongside the binary field.
---

# Always send a `filename` with binary multipart uploads

Several CIB seven REST tools send their payload as `multipart/form-data` with a
binary part named **`data`** or **`content`**. The server and downstream UIs
(Cockpit, Modeler, Tasklist) rely on that part's **filename** to know what the
bytes are and how to display or parse them.

If no `filename` is provided, the server falls back to using the field name
(`"data"` / `"content"`) as the filename — a file with no extension, which often
fails to be parsed, opened, or displayed correctly (a deployed BPMN with no
`.bpmn` extension will not open in Cockpit/Modeler).

## Rule

Whenever a tool call includes a **`data`** or **`content`** field, ALWAYS also
include a **`filename`** argument with a meaningful name **and the correct file
extension**.

## Tools this applies to

| Tool | Binary field | `filename` examples |
|------|--------------|---------------------|
| `createDeployment` | `data` | `process.bpmn`, `decision.dmn`, `case.cmmn`, `myform.form` |
| `addAttachment` | `content` | `invoice.pdf`, `photo.png` |
| `setBinaryTaskVariable` / `setBinaryTaskLocalVariable` | `data` | `report.xlsx` |
| `setProcessInstanceVariableBinary` | `data` | `payload.json` |
| `setLocalExecutionVariableBinary` | `data` | `payload.json` |

This list is not exhaustive: the rule applies to **any** tool whose request body
is `multipart/form-data` and carries a `data` or `content` part.

## How to choose the filename

1. If the user provided a file path or name, reuse it verbatim (keep the extension).
2. Otherwise derive a name from the content and context, and always add the right
   extension:
   - BPMN process → `<processKey>.bpmn`
   - DMN decision → `<decisionKey>.dmn`
   - CMMN case → `<caseKey>.cmmn`
   - CIB/Camunda form → `<formKey>.form`
   - Attachment / binary variable → a descriptive name with the extension matching
     the MIME type (`.pdf`, `.png`, `.json`, `.txt`, …).
3. Never omit the extension.

## Example — deploying a BPMN file

```json
{
  "deployment-name": "my-deployment",
  "deployment-source": "process application",
  "enable-duplicate-filtering": false,
  "filename": "UseICU_Groovy.bpmn",
  "data": "<?xml version=\"1.0\" ...>"
}
```

## Example — adding a PDF attachment to a task

```json
{
  "id": "<taskId>",
  "attachment-name": "Invoice",
  "filename": "invoice-2026-06.pdf",
  "content": "<bytes>"
}
```
