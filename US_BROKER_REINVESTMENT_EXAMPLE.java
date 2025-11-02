package name.abuchen.portfolio.datatransfer.pdf;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import name.abuchen.portfolio.datatransfer.Extractor;
import name.abuchen.portfolio.datatransfer.Extractor.BuySellEntryItem;
import name.abuchen.portfolio.datatransfer.Extractor.Item;
import name.abuchen.portfolio.datatransfer.Extractor.TransactionItem;
import name.abuchen.portfolio.datatransfer.pdf.PDFParser.Block;
import name.abuchen.portfolio.datatransfer.pdf.PDFParser.DocumentType;
import name.abuchen.portfolio.datatransfer.pdf.PDFParser.Transaction;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;

/**
 * Example: US Broker PDF Extractor with Dividend Reinvestment Support
 *
 * This example shows how to:
 * 1. Parse dividend reinvestment lines from US broker statements
 * 2. Split into separate dividend and buy transactions
 * 3. Work with AutoPopulateDividendSharesAction for share quantity
 *
 * Statement Format Example:
 * 01/15/2024  AAPL - DIVIDEND REINVESTED    $450.00    2.49    $180.50
 *             Apple Inc. Common Stock
 *             Dividend on 150 shares
 */
@SuppressWarnings("nls")
public class USBrokerReinvestmentExample extends AbstractPDFExtractor
{
    public USBrokerReinvestmentExample(Client client)
    {
        super(client);

        // Identify this extractor by a unique string in the PDF
        addBankIdentifier("YOUR BROKER NAME");

        // Add document type
        addDocumentType(createDividendReinvestmentType());
    }

    @Override
    public String getLabel()
    {
        return "US Broker Example";
    }

    /**
     * Creates a document type that handles dividend reinvestments
     */
    private DocumentType createDividendReinvestmentType()
    {
        DocumentType type = new DocumentType("Account Statement");

        // Block: Match lines with dividend reinvestment
        Block block = new Block("^[\\d]{2}\\/[\\d]{2}\\/[\\d]{4}\\s+.*(DIVIDEND|DIV).*REINVEST.*$");
        type.addBlock(block);

        // Transaction: Parse dividend with reinvestment data
        Transaction<AccountTransaction> dividendTx = new Transaction<>();
        block.set(dividendTx);

        dividendTx
            .subject(() -> {
                var tx = new AccountTransaction();
                tx.setType(AccountTransaction.Type.DIVIDENDS);
                return tx;
            })

            // @formatter:off
            // Example line:
            // 01/15/2024  AAPL - DIVIDEND REINVESTED    $450.00    2.49    $180.50
            // @formatter:on
            .section("date", "name", "currency", "amount", "reinvestShares", "reinvestPrice")
            .match("^(?<date>[\\d]{2}\\/[\\d]{2}\\/[\\d]{4})\\s+"
                    + "(?<name>.*) \\- DIVIDEND REINVESTED\\s+"
                    + "\\p{Sc}(?<amount>[\\.,\\d]+)\\s+"
                    + "(?<reinvestShares>[\\.,\\d]+)\\s+"
                    + "\\p{Sc}(?<reinvestPrice>[\\.,\\d]+)$")
            .assign((t, v) -> {
                // Store reinvestment data for postProcessing
                v.getTransactionContext().put("hasReinvestment", "true");
                v.getTransactionContext().put("reinvestShares", v.get("reinvestShares"));
                v.getTransactionContext().put("reinvestPrice", v.get("reinvestPrice"));

                // Set basic transaction info
                t.setDateTime(asDate(v.get("date")));
                t.setCurrencyCode(asCurrencyCode("USD")); // Or parse from v.get("currency")
                t.setAmount(asAmount(v.get("amount")));

                // Create security
                t.setSecurity(getOrCreateSecurity(v));
            })

            // @formatter:off
            // Optional: Security name on next line
            //             Apple Inc. Common Stock
            // @formatter:on
            .section("nameContinued").optional()
            .match("^\\s+(?<nameContinued>.*)$")
            .assign((t, v) -> {
                var security = t.getSecurity();
                if (security != null && v.get("nameContinued") != null)
                {
                    security.setName(security.getName() + " " + v.get("nameContinued"));
                }
            })

            // @formatter:off
            // Optional: Dividend shares on next line
            //             Dividend on 150 shares
            // @formatter:on
            .section("dividendShares").optional()
            .match("^\\s+Dividend on (?<dividendShares>[\\.,\\d]+) shares?$")
            .assign((t, v) -> {
                // If the statement shows dividend shares, use them
                // Otherwise, AutoPopulateDividendSharesAction will fill this
                t.setShares(asShares(v.get("dividendShares")));
            })

            .wrap((t, ctx) -> {
                TransactionItem item = new TransactionItem(t);

                // Store reinvestment data in the item for postProcessing
                if ("true".equals(ctx.getString("hasReinvestment")))
                {
                    item.setData("hasReinvestment", "true");
                    item.setData("reinvestShares", ctx.getString("reinvestShares"));
                    item.setData("reinvestPrice", ctx.getString("reinvestPrice"));
                    item.setData("security", t.getSecurity());
                    item.setData("date", t.getDateTime());
                    item.setData("currency", t.getCurrencyCode());
                }

                return item;
            });

        return type;
    }

    /**
     * PostProcessing: Split reinvested dividends into dividend + buy transactions
     */
    @Override
    public void postProcessing(List<Item> items)
    {
        List<Item> buyTransactions = new ArrayList<>();

        for (Item item : items)
        {
            // Only process transaction items
            if (!(item instanceof TransactionItem))
                continue;

            var transactionItem = (TransactionItem) item;

            // Only process account transactions
            if (!(transactionItem.getSubject() instanceof AccountTransaction))
                continue;

            var dividend = (AccountTransaction) transactionItem.getSubject();

            // Only process dividends
            if (dividend.getType() != AccountTransaction.Type.DIVIDENDS)
                continue;

            // Check if this dividend has reinvestment data
            if (!"true".equals(item.getData().get("hasReinvestment")))
                continue;

            // Extract reinvestment details from item data
            String reinvestSharesStr = (String) item.getData().get("reinvestShares");
            String reinvestPriceStr = (String) item.getData().get("reinvestPrice");

            if (reinvestSharesStr == null || reinvestPriceStr == null)
                continue;

            try
            {
                // Create the buy transaction from reinvestment
                BuySellEntry buy = createBuyFromReinvestment(
                    dividend,
                    reinvestSharesStr,
                    reinvestPriceStr
                );

                // Add to list of new items
                buyTransactions.add(new BuySellEntryItem(buy));

                // Optional: Add note to dividend indicating it was reinvested
                String originalNote = dividend.getNote();
                String reinvestNote = String.format("Reinvested: %s shares @ %s",
                        reinvestSharesStr, reinvestPriceStr);
                dividend.setNote(originalNote == null ? reinvestNote :
                        originalNote + " | " + reinvestNote);
            }
            catch (Exception e)
            {
                // Log error but don't fail the entire import
                System.err.println("Failed to create buy transaction from reinvestment: " + e.getMessage());
            }
        }

        // Add all buy transactions to the items list
        items.addAll(buyTransactions);
    }

    /**
     * Creates a buy transaction from reinvestment data
     */
    private BuySellEntry createBuyFromReinvestment(
            AccountTransaction dividend,
            String reinvestSharesStr,
            String reinvestPriceStr)
    {
        BuySellEntry buy = new BuySellEntry();
        buy.setType(PortfolioTransaction.Type.BUY);

        // Copy from dividend
        buy.setSecurity(dividend.getSecurity());
        buy.setDate(dividend.getDateTime());

        // Set shares purchased
        long shares = asShares(reinvestSharesStr);
        buy.setShares(shares);

        // Calculate buy amount: shares * price
        BigDecimal price = asBigDecimal(reinvestPriceStr);
        BigDecimal shareCount = BigDecimal.valueOf(shares)
                .divide(BigDecimal.valueOf(Values.Share.factor()), 10, RoundingMode.HALF_UP);
        BigDecimal totalAmount = shareCount.multiply(price);

        Money buyAmount = Money.of(
            dividend.getCurrencyCode(),
            Math.round(totalAmount.doubleValue() * Values.Amount.factor())
        );
        buy.setMonetaryAmount(buyAmount);

        // Set metadata
        buy.setSource(dividend.getSource());
        buy.setNote("Auto-generated from dividend reinvestment");

        return buy;
    }
}
