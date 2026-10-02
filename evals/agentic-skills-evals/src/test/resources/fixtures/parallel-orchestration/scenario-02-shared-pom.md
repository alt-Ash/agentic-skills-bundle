# Scenario 02: Slices that collide on pom.xml

## Request

Implement the "order export" feature for the Prism API. The decomposition is already agreed — build it now, no OpenSpec, no clarifying questions, no ticket to pull.

Spring Boot 3, Maven single-module, base package `com.prism`.

| Slice | Work | Files (complete list) |
|---|---|---|
| 1 | CSV export endpoint; adds the `commons-csv` dependency | `src/main/java/com/prism/export/CsvExportController.java`, `pom.xml` |
| 2 | PDF export endpoint; adds the `openpdf` dependency | `src/main/java/com/prism/export/PdfExportController.java`, `pom.xml` |

Facts already verified (treat as complete and accurate): the file lists above are complete and correct.
