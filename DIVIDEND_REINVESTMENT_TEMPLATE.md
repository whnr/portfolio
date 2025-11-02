# Template: Splitting Reinvested Dividends in PDF Importers

This template shows how to handle dividend reinvestment where a single line contains both:
1. A dividend payment
2. Shares purchased from reinvesting that dividend

## Example US Broker Statement Format

```
Date        Description                           Amount      Shares    Price
01/15/2024  AAPL - DIVIDEND REINVESTED           $450.00     2.49      $180.50
            Apple Inc. Common Stock
            Dividend on 150 shares
```

This should create TWO transactions:
1. **Dividend:** $450.00 on 150 shares
2. **Buy:** 2.49 shares @ $180.50 = $449.45

## Implementation Pattern

### Step 1: Parse and Store Reinvestment Data

```java
// In your PDFExtractor class, parse the dividend transaction
Block dividendBlock = new Block("^.*(DIVIDEND|DIV).*REINVEST.*$");

Transaction<AccountTransaction> dividendTx = dividendBlock.transaction(AccountTransaction.class)
    .subject(() -> {
        var tx = new AccountTransaction();
        tx.setType(AccountTransaction.Type.DIVIDENDS);
        return tx;
    })

    // ... standard security, date, amount parsing ...

    // Capture reinvestment details
    .section("reinvestShares", "reinvestPrice").optional()
    .match("^.* DIVIDEND REINVESTED\\s+[\\.,\\d]+\\s+(?<reinvestShares>[\\.,\\d]+)\\s+(?<reinvestPrice>[\\.,\\d]+)$")
    .assign((t, v) -> {
        // Store in transaction context for postProcessing
        v.getTransactionContext().put("hasReinvestment", "true");
        v.getTransactionContext().put("reinvestShares", v.get("reinvestShares"));
        v.getTransactionContext().put("reinvestPrice", v.get("reinvestPrice"));
    })

    // Optionally capture dividend shares (if shown in statement)
    .section("dividendShares").optional()
    .match("^.*Dividend on (?<dividendShares>[\\.,\\d]+) shares.*$")
    .assign((t, v) -> {
        t.setShares(asShares(v.get("dividendShares")));
    })

    .wrap(TransactionItem::new);
```

### Step 2: Split in postProcessing

```java
@Override
public void postProcessing(List<Item> items)
{
    List<Item> newBuyTransactions = new ArrayList<>();

    for (Item item : items)
    {
        if (!(item instanceof TransactionItem))
            continue;

        var transactionItem = (TransactionItem) item;
        if (!(transactionItem.getSubject() instanceof AccountTransaction))
            continue;

        var dividend = (AccountTransaction) transactionItem.getSubject();
        if (dividend.getType() != AccountTransaction.Type.DIVIDENDS)
            continue;

        // Check if this dividend has reinvestment data
        var data = item.getData();
        if (!"true".equals(data.get("hasReinvestment")))
            continue;

        // Extract reinvestment details
        String reinvestSharesStr = (String) data.get("reinvestShares");
        String reinvestPriceStr = (String) data.get("reinvestPrice");

        if (reinvestSharesStr == null || reinvestPriceStr == null)
            continue;

        // Create the buy transaction
        BuySellEntry buy = new BuySellEntry();
        buy.setType(PortfolioTransaction.Type.BUY);
        buy.setSecurity(dividend.getSecurity());
        buy.setDate(dividend.getDateTime());
        buy.setShares(asShares(reinvestSharesStr));

        // Calculate buy amount from shares * price
        long shares = asShares(reinvestSharesStr);
        BigDecimal price = asBigDecimal(reinvestPriceStr);
        BigDecimal amount = BigDecimal.valueOf(shares)
                .divide(BigDecimal.valueOf(Values.Share.factor()), 10, RoundingMode.HALF_UP)
                .multiply(price);
        Money buyAmount = Money.of(dividend.getCurrencyCode(),
                Math.round(amount.doubleValue() * Values.Amount.factor()));

        buy.setMonetaryAmount(buyAmount);

        // Copy source information
        buy.setSource(dividend.getSource());
        buy.setNote("Auto-generated from dividend reinvestment");

        // Add to list of new transactions
        newBuyTransactions.add(new BuySellEntryItem(buy));
    }

    // Add all buy transactions to the items list
    items.addAll(newBuyTransactions);
}
```

## Alternative: Full Cash Dividend + Separate Buy

If the broker shows the dividend as full cash, but then reinvests it:

```java
// The dividend remains unchanged (full cash amount)
// Just create the buy with the same amount

@Override
public void postProcessing(List<Item> items)
{
    for (Item item : items)
    {
        // ... same checks as above ...

        // Create buy with same amount as dividend
        BuySellEntry buy = new BuySellEntry();
        buy.setType(PortfolioTransaction.Type.BUY);
        buy.setSecurity(dividend.getSecurity());
        buy.setDate(dividend.getDateTime());
        buy.setShares(asShares(reinvestSharesStr));
        buy.setMonetaryAmount(dividend.getMonetaryAmount());

        items.add(new BuySellEntryItem(buy));
    }
}
```

## Test Case Pattern

```java
@Test
public void testDividendReinvestment01()
{
    var results = extractor.extract(PDFInputFile.loadTestCase(getClass(), "DividendReinvest01.txt"), errors);

    assertThat(errors, empty());
    assertThat(results.size(), is(2)); // Dividend + Buy

    // Check dividend transaction
    assertThat(results.get(0), dividend(
        hasDate("2024-01-15"),
        hasShares(150),  // Shares that generated dividend
        hasAmount("USD", 450.00),
        hasSecurity("Apple Inc.")));

    // Check buy transaction (from reinvestment)
    assertThat(results.get(1), purchase(
        hasDate("2024-01-15"),
        hasShares(2.49),  // Shares purchased
        hasAmount("USD", 449.45),
        hasSecurity("Apple Inc.")));
}
```

## Data Flow with Auto-Population

```
┌─────────────────────────────────────────────────────────────┐
│ PDF Parsing Phase                                           │
│ • Extract: "DIVIDEND REINVESTED $450 / 2.49 shares @ $180" │
│ • Store reinvestment data in item.getData()                │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│ PostProcessing Phase (PDF Extractor)                        │
│ • Detect reinvestment flag                                  │
│ • Create BuySellEntry: 2.49 shares @ $180.50               │
│ • Keep AccountTransaction: $450 (shares=0 if not in PDF)   │
│ Result: [Dividend Item, Buy Item]                          │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│ Import Wizard Review (ReviewExtractedItemsPage)            │
│ • User sees 2 transactions in table                         │
│ • Checkbox: "Auto-populate dividend shares" appears        │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│ ImportAction Phase (if checkbox checked)                    │
│ • AutoPopulateDividendSharesAction runs                     │
│ • Looks up holdings: "User had 150 shares on 01/15"        │
│ • Sets dividend.setShares(150)                              │
│ Result: Dividend now shows 150 shares!                      │
└─────────────────────────────────────────────────────────────┘
```

## Key Points

1. **Reinvestment splitting** = PDF-specific parsing logic (postProcessing)
2. **Share quantity filling** = General business logic (ImportAction)
3. **Both work together** = Split first, then auto-populate shares
4. **User control** = Checkbox for auto-population, automatic splitting
5. **Works for all cases**:
   - Statement includes dividend shares → Use those
   - Statement doesn't include dividend shares → Auto-populate from holdings

## Common Patterns

### Pattern A: Statement shows everything
```
DIVIDEND REINVESTED - AAPL - 150 shares - $450.00
Reinvested: 2.49 shares @ $180.50
```
→ Parse all data, split in postProcessing

### Pattern B: Statement shows reinvestment only
```
DIVIDEND REINVESTED - AAPL - $450.00
Purchased: 2.49 shares @ $180.50
```
→ Parse reinvestment, split in postProcessing, shares=0 → auto-populate with ImportAction

### Pattern C: Separate lines
```
Line 1: DIVIDEND - AAPL - $450.00
Line 2: REINVESTMENT - AAPL - 2.49 shares @ $180.50
```
→ Parse as two separate transactions, let normal flow handle

## Integration with Existing Code

Your AutoPopulateDividendSharesAction will automatically work because:
- It runs AFTER postProcessing
- It processes the items list that now has both dividend + buy
- It fills shares for dividends with shares=0
- Buy transactions already have shares from parsing

No changes needed to AutoPopulateDividendSharesAction!
