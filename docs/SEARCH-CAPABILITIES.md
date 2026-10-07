# Chat search capabilities

This inventory describes the checked-in stock 0.154.0 protocol in
`protocol/stock-0.154.0-ClientRequest.json`, specifically `ThreadListParams`,
`ThreadSearchParams`, and their sort enums. It is a versioned implementation
baseline, not a claim about every Codex client or future server version.

| Faceted-search component | Stock service | Remote Codex Android |
| --- | --- | --- |
| Text query | List: title substring. Search: required substring/full-text query. | Nonempty text uses `thread/search`; never silently substitutes title-only search. |
| Project scope | List: one exact project, unassigned, or all. Search has no project parameter. | Multiple project checkboxes, optionally including No project. Filters server pages by assignment. |
| Combining filters | List offers project and other metadata filters; search offers fewer. | OR within selected projects; AND with query and archive view. |
| Active/archive | Both endpoints offer a boolean archive filter; default is active. | Separate active and archived views, with their own list selections. |
| Sorting | Both: creation, update, recency, ascending/descending. List additionally supports section position. | Recent activity, newest created, oldest created. |
| Pagination | Opaque cursor and page size on both endpoints. | Loads additional pages; scoped filtering scans past pages without matches. Deduplicates thread IDs and rejects repeated cursors. |
| Source/type | Both offer source kinds; defaults are interactive sources. | Keeps the server default to exclude internal sessions. |
| Workspace/provider | List offers exact cwd path(s) and model provider filters. Search does not. | No controls. Workspace paths are not project identities. |
| Section/parent/ancestor | List only; parent and ancestor are mutually exclusive. | No controls. |
| Originator | List only; nonempty filters are hosted-backend-only. | No control on the local service. |
| Facet counts/total count | No count or aggregation request fields in these endpoints. | No per-project counts or exact total. |
| Date ranges/status/unread/tags | No filter fields in these endpoints. | No search controls for these. Sort timestamps and activity indicators do not imply filtering support. |
| Relevance/advanced query syntax | No relevance sort or dedicated Boolean, fuzzy, phrase, or prefix controls in the schema. | Passes text to the server; no promise of advanced grammar. |
| Snippets/highlighting | Not established by the request schema. | Current list displays thread titles and metadata, not highlighted match previews. |
| Clear/reset/cancel | Client interaction. | Clear text, reset project/sort, apply atomically, dismiss without applying. |
| Saved searches | No saved-search operations used by this client. | List choices survive navigation within the session; no named saved-search UI. |

Sparse selected scopes can take longer because full-text search and multi-project
browsing must read and filter multiple server pages. An unfinished or failed scan
must not be presented as an exhaustive negative result. Projects removed from the
server catalog are removed from the selection on catalog refresh; an empty scope
returns to All projects.
