# Dividend Reinvestment Implementation Guide

## Quick Answer to Your Questions

### Q: Should dividend share auto-population be in postProcessing or general?
**A: Keep it as a general ImportAction** ✅ (already implemented!)

**Why:**
- Works for ALL import types (PDF, CSV, Interactive Brokers, etc.)
- User has UI control via checkbox
- Broker-agnostic logic
- Already implemented and working in `AutoPopulateDividendSharesAction`

### Q: Want a template for splitting reinvested dividends?
**A: See the complete working example below** ✅

## Architecture Decision

```
┌─────────────────────────────────────────────────────────────────┐
│ PDF Extractor postProcessing()                                  │
│ • Parse reinvestment data from broker statement                 │
│ • Split: 1 line → 2 transactions (dividend + buy)              │
│ • Broker-specific format handling                               │
└────────────────────┬────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│ AutoPopulateDividendSharesAction (General ImportAction)         │
│ • Fill missing shares on dividends (all import types!)          │
│ • User opt-in via checkbox                                      │
│ • Uses existing holdings + current import transactions          │
└─────────────────────────────────────────────────────────────────┘
```

## Files Created for You

### 1. **DIVIDEND_REINVESTMENT_TEMPLATE.md**
Complete conceptual guide with:
- Theory and patterns
- Data flow diagrams
- Multiple implementation options
- Integration with existing AutoPopulateDividendSharesAction

### 2. **US_BROKER_REINVESTMENT_EXAMPLE.java**
Working, copy-pasteable code:
- Full PDF extractor implementation
- Parsing with reinvestment detection
- postProcessing to split transactions
- Commented and production-ready

### 3. **US_BROKER_REINVESTMENT_TEST_EXAMPLE.java**
Complete test suite:
- Test with dividend shares included
- Test without dividend shares (auto-population)
- Test with multiple securities
- Modern ExtractorMatchers usage

### 4. **DividendReinvest0X_EXAMPLE.txt**
Sample PDF text files showing:
- Format with dividend shares
- Format without dividend shares
- Multiple securities on same statement

## Quick Start: Adapt for Your Broker

### Step 1: Copy the Example Extractor
```bash
cp US_BROKER_REINVESTMENT_EXAMPLE.java \
   name.abuchen.portfolio/src/.../pdf/YourBrokerPDFExtractor.java
```

### Step 2: Update Bank Identifier
```java
addBankIdentifier("Your Actual Broker Name From PDF");
```

### Step 3: Adjust Regex Patterns
Find the pattern in your broker's PDF:
```
Original example:
01/15/2024  AAPL - DIVIDEND REINVESTED    $450.00    2.49    $180.50

Your broker might be:
2024-01-15  DIV REINV  AAPL  $450.00  2.49 shs @ $180.50
```

Update the `.match()` regex to match YOUR format.

### Step 4: Copy Test Template
```bash
cp US_BROKER_REINVESTMENT_TEST_EXAMPLE.java \
   name.abuchen.portfolio.tests/src/.../YourBrokerPDFExtractorTest.java
```

### Step 5: Create Test Case File
1. Export real PDF statement (anonymize account numbers!)
2. Use PP menu: `Help` → `Create Test Case`
3. Save as `DividendReinvest01.txt` in test resources

### Step 6: Run Tests
```bash
mvn test -Dtest=YourBrokerPDFExtractorTest
```

## How It Works Together

### Import Flow:
```
1. User imports PDF
   ↓
2. YourBrokerPDFExtractor.parse()
   • Extracts: "DIV REINVEST - AAPL - $450 / 2.49 shares @ $180"
   • Stores reinvestment data
   ↓
3. YourBrokerPDFExtractor.postProcessing()
   • Detects reinvestment flag
   • Creates: [Dividend($450, shares=0), Buy(2.49 shares @ $180)]
   ↓
4. ReviewExtractedItemsPage
   • Shows 2 transactions in UI table
   • User sees checkbox: "Auto-populate dividend shares"
   • User checks the box ✓
   ↓
5. AutoPopulateDividendSharesAction.process()
   • Looks up holdings: User had 150 shares on 01/15
   • Sets: Dividend.shares = 150
   ↓
6. Final Result:
   ✓ Dividend: 150 shares → $450
   ✓ Buy: 2.49 shares @ $180.50 = $449.45
```

## Key Implementation Points

### ✅ DO:
- Parse all data during extraction phase
- Store reinvestment metadata in `item.getData()`
- Create buy transactions in `postProcessing()`
- Let AutoPopulateDividendSharesAction handle missing shares
- Add clear notes to show reinvestment relationship

### ❌ DON'T:
- Try to look up holdings in PDF extractor (use ImportAction instead)
- Duplicate share calculation logic in extractor
- Assume dividend shares are always present
- Forget to copy source/date/security from dividend to buy

## Testing Checklist

- [ ] Test with dividend shares shown in statement
- [ ] Test without dividend shares (relies on auto-population)
- [ ] Test with multiple securities
- [ ] Test with fractional shares
- [ ] Test with different currencies
- [ ] Test edge case: reinvestment amount ≠ dividend amount
- [ ] Test that checkbox appears when importing
- [ ] Test that checkbox correctly fills shares

## Example Regex Patterns for US Brokers

### Pattern 1: Tab-separated with price
```java
.match("^(?<date>[\\d]{2}\\/[\\d]{2}\\/[\\d]{4})\\t"
    + "(?<name>[^\\t]+) \\- DIVIDEND REINVESTED\\t"
    + "\\$(?<amount>[\\.,\\d]+)\\t"
    + "(?<reinvestShares>[\\.,\\d]+)\\t"
    + "\\$(?<reinvestPrice>[\\.,\\d]+)$")
```

### Pattern 2: Space-separated
```java
.match("^(?<date>[\\d]{2}\\/[\\d]{2}\\/[\\d]{4})\\s+"
    + "DIV REINV\\s+"
    + "(?<name>\\w+)\\s+"
    + "\\$(?<amount>[\\.,\\d]+)\\s+"
    + "(?<reinvestShares>[\\.,\\d]+) shs @ \\$(?<reinvestPrice>[\\.,\\d]+)$")
```

### Pattern 3: Multi-line format
```java
// Line 1: Date and security
.match("^(?<date>[\\d]{2}\\/[\\d]{2}\\/[\\d]{4})\\s+(?<name>.*) DIVIDEND$")
// Line 2: Reinvestment details
.match("^\\s+REINVESTED:\\s+\\$(?<amount>[\\.,\\d]+) → "
    + "(?<reinvestShares>[\\.,\\d]+) sh @ \\$(?<reinvestPrice>[\\.,\\d]+)$")
```

## Common Gotchas

### 1. Rounding Differences
```java
// Broker shows: $450 / 2.49 shares @ $180.50 = $449.445
// But statement shows: $449.45 (rounded)

// Solution: Calculate from shares * price, then round:
BigDecimal amount = shareCount.multiply(price)
    .setScale(2, RoundingMode.HALF_UP);
```

### 2. Currency Symbols
```java
// Use generic currency symbol matcher:
.match(".*\\p{Sc}(?<amount>[\\.,\\d]+).*")  // Matches $, €, £, etc.
```

### 3. Fractional Shares
```java
// Use US locale for parsing shares:
t.setShares(asShares(v.get("reinvestShares"), "en", "US"));
// Handles: "2.49" (US) vs "2,49" (EU)
```

### 4. Date Formats
```java
// US format: MM/DD/YYYY
asDate(v.get("date"), "MM/dd/yyyy")

// ISO format: YYYY-MM-DD
asDate(v.get("date"), "yyyy-MM-dd")
```

## Support and Next Steps

1. **Review**: Read `DIVIDEND_REINVESTMENT_TEMPLATE.md` for theory
2. **Copy**: Start with `US_BROKER_REINVESTMENT_EXAMPLE.java`
3. **Adapt**: Modify regex for your broker's format
4. **Test**: Use `US_BROKER_REINVESTMENT_TEST_EXAMPLE.java` as guide
5. **Verify**: Check that AutoPopulateDividendSharesAction fills shares
6. **Commit**: Follow CONTRIBUTING.md guidelines

## Summary

**Decision**: Keep dividend share auto-population as general ImportAction ✅
**Template**: Complete working example provided ✅
**Integration**: Works seamlessly with existing AutoPopulateDividendSharesAction ✅
**Testing**: Full test suite included ✅

You now have everything needed to implement reinvested dividend splitting for any US broker!
