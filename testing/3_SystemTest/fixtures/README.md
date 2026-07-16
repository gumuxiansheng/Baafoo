# Complex Test Case Fixtures

Test data for the CX (CompleX) test section in `test-fullchain.sh` / `.ps1`.

| File | Used by | Description |
|------|---------|-------------|
| `soap-request.xml` | CX02 | SOAP envelope with `GetUserQuote` operation |
| `nested-json.json` | CX04 | Deeply-nested JSON for JsonPath `$.data.orders[0].items[2].sku` |
| `complex-order.json` | CX03 | Multi-field order JSON for 5-condition AND match |
| `cjk-payload.json` | CX06 | JSON with CJK (Chinese) content for body contains match |
| `array-payload.json` | CX04 | JSON array for `$.items[1].status` JsonPath match |

CX01 (large payload) is generated dynamically in the test script (~50KB).
