package com.baafoo.server.api.dto;

import java.util.List;

public class CsvImportResponse {
    public int created;
    public int skipped;
    public int failed;
    public List<String> errors;
}
