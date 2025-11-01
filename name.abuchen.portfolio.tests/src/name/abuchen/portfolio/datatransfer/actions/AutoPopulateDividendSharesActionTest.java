package name.abuchen.portfolio.datatransfer.actions;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.time.LocalDateTime;
import java.util.Collections;

import org.junit.Test;

import name.abuchen.portfolio.datatransfer.ImportAction.Status;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.CurrencyUnit;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;

@SuppressWarnings("nls")
public class AutoPopulateDividendSharesActionTest
{
    @Test
    public void testDividendWithZeroSharesAndExistingHoldings()
    {
        // Setup: Create a client with a security holding
        Client client = new Client();
        Security security = new Security("Apple Inc.", CurrencyUnit.USD);
        client.addSecurity(security);

        Account account = new Account("Brokerage Account");
        account.setCurrencyCode(CurrencyUnit.USD);
        client.addAccount(account);

        Portfolio portfolio = new Portfolio("Stock Portfolio");
        portfolio.setReferenceAccount(account);
        client.addPortfolio(portfolio);

        // Buy 100 shares on Jan 1, 2024
        BuySellEntry buy = new BuySellEntry();
        buy.setType(PortfolioTransaction.Type.BUY);
        buy.setDate(LocalDateTime.parse("2024-01-01T00:00:00"));
        buy.setShares(100 * Values.Share.factor());
        buy.setMonetaryAmount(Money.of(CurrencyUnit.USD, 15000_00));
        buy.setSecurity(security);
        buy.setNote("Initial purchase");
        buy.insert(account, portfolio);

        // Create dividend transaction on Jan 15, 2024 with 0 shares
        AccountTransaction dividend = new AccountTransaction();
        dividend.setType(AccountTransaction.Type.DIVIDENDS);
        dividend.setDateTime(LocalDateTime.parse("2024-01-15T00:00:00"));
        dividend.setSecurity(security);
        dividend.setShares(0); // Not set by importer
        dividend.setMonetaryAmount(Money.of(CurrencyUnit.USD, 100_00));

        // Execute
        AutoPopulateDividendSharesAction action = new AutoPopulateDividendSharesAction(client, true,
                        Collections.emptySet());
        Status status = action.process(dividend, account);

        // Assert
        assertThat(status.getCode(), is(Status.Code.OK));
        assertThat(dividend.getShares(), is(100 * Values.Share.factor()));
    }

    @Test
    public void testDividendWithSharesAlreadySet()
    {
        // Setup
        Client client = new Client();
        Security security = new Security("Apple Inc.", CurrencyUnit.USD);
        client.addSecurity(security);

        Account account = new Account("Brokerage Account");
        account.setCurrencyCode(CurrencyUnit.USD);
        client.addAccount(account);

        Portfolio portfolio = new Portfolio("Stock Portfolio");
        portfolio.setReferenceAccount(account);
        client.addPortfolio(portfolio);

        // Buy 100 shares
        BuySellEntry buy = new BuySellEntry();
        buy.setType(PortfolioTransaction.Type.BUY);
        buy.setDate(LocalDateTime.parse("2024-01-01T00:00:00"));
        buy.setShares(100 * Values.Share.factor());
        buy.setMonetaryAmount(Money.of(CurrencyUnit.USD, 15000_00));
        buy.setSecurity(security);
        buy.insert(account, portfolio);

        // Create dividend with shares already set to 50
        AccountTransaction dividend = new AccountTransaction();
        dividend.setType(AccountTransaction.Type.DIVIDENDS);
        dividend.setDateTime(LocalDateTime.parse("2024-01-15T00:00:00"));
        dividend.setSecurity(security);
        dividend.setShares(50 * Values.Share.factor()); // Already set
        dividend.setMonetaryAmount(Money.of(CurrencyUnit.USD, 50_00));

        // Execute
        AutoPopulateDividendSharesAction action = new AutoPopulateDividendSharesAction(client, true,
                        Collections.emptySet());
        Status status = action.process(dividend, account);

        // Assert: Should not modify existing shares
        assertThat(status.getCode(), is(Status.Code.OK));
        assertThat(dividend.getShares(), is(50 * Values.Share.factor()));
    }

    @Test
    public void testDividendWithNoHoldings()
    {
        // Setup: Client with security but no holdings
        Client client = new Client();
        Security security = new Security("Apple Inc.", CurrencyUnit.USD);
        client.addSecurity(security);

        Account account = new Account("Brokerage Account");
        account.setCurrencyCode(CurrencyUnit.USD);
        client.addAccount(account);

        // Create dividend for security with no holdings
        AccountTransaction dividend = new AccountTransaction();
        dividend.setType(AccountTransaction.Type.DIVIDENDS);
        dividend.setDateTime(LocalDateTime.parse("2024-01-15T00:00:00"));
        dividend.setSecurity(security);
        dividend.setShares(0);
        dividend.setMonetaryAmount(Money.of(CurrencyUnit.USD, 100_00));

        // Execute
        AutoPopulateDividendSharesAction action = new AutoPopulateDividendSharesAction(client, true,
                        Collections.emptySet());
        Status status = action.process(dividend, account);

        // Assert: Should remain 0
        assertThat(status.getCode(), is(Status.Code.OK));
        assertThat(dividend.getShares(), is(0L));
    }

    @Test
    public void testNonDividendTransaction()
    {
        // Setup
        Client client = new Client();
        Account account = new Account("Checking Account");
        account.setCurrencyCode(CurrencyUnit.USD);
        client.addAccount(account);

        // Create a DEPOSIT transaction (not a dividend)
        AccountTransaction deposit = new AccountTransaction();
        deposit.setType(AccountTransaction.Type.DEPOSIT);
        deposit.setDateTime(LocalDateTime.parse("2024-01-15T00:00:00"));
        deposit.setShares(0);
        deposit.setMonetaryAmount(Money.of(CurrencyUnit.USD, 1000_00));

        // Execute
        AutoPopulateDividendSharesAction action = new AutoPopulateDividendSharesAction(client);
        Status status = action.process(deposit, account);

        // Assert: Should ignore non-dividend transactions
        assertThat(status.getCode(), is(Status.Code.OK));
        assertThat(deposit.getShares(), is(0L));
    }

    @Test
    public void testDividendWithNoSecurity()
    {
        // Setup
        Client client = new Client();
        Account account = new Account("Brokerage Account");
        account.setCurrencyCode(CurrencyUnit.USD);
        client.addAccount(account);

        // Create dividend without a security
        AccountTransaction dividend = new AccountTransaction();
        dividend.setType(AccountTransaction.Type.DIVIDENDS);
        dividend.setDateTime(LocalDateTime.parse("2024-01-15T00:00:00"));
        dividend.setSecurity(null); // No security
        dividend.setShares(0);
        dividend.setMonetaryAmount(Money.of(CurrencyUnit.USD, 100_00));

        // Execute
        AutoPopulateDividendSharesAction action = new AutoPopulateDividendSharesAction(client, true,
                        Collections.emptySet());
        Status status = action.process(dividend, account);

        // Assert: Should handle gracefully
        assertThat(status.getCode(), is(Status.Code.OK));
        assertThat(dividend.getShares(), is(0L));
    }

    @Test
    public void testSharesHeldOnSpecificDate()
    {
        // Setup: Buy and sell transactions around dividend date
        Client client = new Client();
        Security security = new Security("Microsoft Corp.", CurrencyUnit.USD);
        client.addSecurity(security);

        Account account = new Account("Brokerage Account");
        account.setCurrencyCode(CurrencyUnit.USD);
        client.addAccount(account);

        Portfolio portfolio = new Portfolio("Stock Portfolio");
        portfolio.setReferenceAccount(account);
        client.addPortfolio(portfolio);

        // Buy 100 shares on Jan 1
        BuySellEntry buy1 = new BuySellEntry();
        buy1.setType(PortfolioTransaction.Type.BUY);
        buy1.setDate(LocalDateTime.parse("2024-01-01T00:00:00"));
        buy1.setShares(100 * Values.Share.factor());
        buy1.setMonetaryAmount(Money.of(CurrencyUnit.USD, 10000_00));
        buy1.setSecurity(security);
        buy1.insert(account, portfolio);

        // Buy 50 more shares on Jan 10
        BuySellEntry buy2 = new BuySellEntry();
        buy2.setType(PortfolioTransaction.Type.BUY);
        buy2.setDate(LocalDateTime.parse("2024-01-10T00:00:00"));
        buy2.setShares(50 * Values.Share.factor());
        buy2.setMonetaryAmount(Money.of(CurrencyUnit.USD, 5000_00));
        buy2.setSecurity(security);
        buy2.insert(account, portfolio);

        // Dividend on Jan 15 (should have 150 shares)
        AccountTransaction dividend = new AccountTransaction();
        dividend.setType(AccountTransaction.Type.DIVIDENDS);
        dividend.setDateTime(LocalDateTime.parse("2024-01-15T00:00:00"));
        dividend.setSecurity(security);
        dividend.setShares(0);
        dividend.setMonetaryAmount(Money.of(CurrencyUnit.USD, 150_00));

        // Execute
        AutoPopulateDividendSharesAction action = new AutoPopulateDividendSharesAction(client, true,
                        Collections.emptySet());
        Status status = action.process(dividend, account);

        // Assert: Should calculate 150 shares held on Jan 15
        assertThat(status.getCode(), is(Status.Code.OK));
        assertThat(dividend.getShares(), is(150 * Values.Share.factor()));
    }

    @Test
    public void testSharesHeldAfterPartialSale()
    {
        // Setup: Buy, sell, then dividend
        Client client = new Client();
        Security security = new Security("Tesla Inc.", CurrencyUnit.USD);
        client.addSecurity(security);

        Account account = new Account("Brokerage Account");
        account.setCurrencyCode(CurrencyUnit.USD);
        client.addAccount(account);

        Portfolio portfolio = new Portfolio("Stock Portfolio");
        portfolio.setReferenceAccount(account);
        client.addPortfolio(portfolio);

        // Buy 200 shares on Jan 1
        BuySellEntry buy = new BuySellEntry();
        buy.setType(PortfolioTransaction.Type.BUY);
        buy.setDate(LocalDateTime.parse("2024-01-01T00:00:00"));
        buy.setShares(200 * Values.Share.factor());
        buy.setMonetaryAmount(Money.of(CurrencyUnit.USD, 40000_00));
        buy.setSecurity(security);
        buy.insert(account, portfolio);

        // Sell 75 shares on Jan 10
        BuySellEntry sell = new BuySellEntry();
        sell.setType(PortfolioTransaction.Type.SELL);
        sell.setDate(LocalDateTime.parse("2024-01-10T00:00:00"));
        sell.setShares(75 * Values.Share.factor());
        sell.setMonetaryAmount(Money.of(CurrencyUnit.USD, 15000_00));
        sell.setSecurity(security);
        sell.insert(account, portfolio);

        // Dividend on Jan 15 (should have 125 shares remaining)
        AccountTransaction dividend = new AccountTransaction();
        dividend.setType(AccountTransaction.Type.DIVIDENDS);
        dividend.setDateTime(LocalDateTime.parse("2024-01-15T00:00:00"));
        dividend.setSecurity(security);
        dividend.setShares(0);
        dividend.setMonetaryAmount(Money.of(CurrencyUnit.USD, 125_00));

        // Execute
        AutoPopulateDividendSharesAction action = new AutoPopulateDividendSharesAction(client, true,
                        Collections.emptySet());
        Status status = action.process(dividend, account);

        // Assert: Should calculate 125 shares held on Jan 15
        assertThat(status.getCode(), is(Status.Code.OK));
        assertThat(dividend.getShares(), is(125 * Values.Share.factor()));
    }

    @Test
    public void testDoNotCountFutureTransactions()
    {
        // Setup: Dividend should not count shares bought AFTER the dividend date
        Client client = new Client();
        Security security = new Security("Amazon.com Inc.", CurrencyUnit.USD);
        client.addSecurity(security);

        Account account = new Account("Brokerage Account");
        account.setCurrencyCode(CurrencyUnit.USD);
        client.addAccount(account);

        Portfolio portfolio = new Portfolio("Stock Portfolio");
        portfolio.setReferenceAccount(account);
        client.addPortfolio(portfolio);

        // Buy 100 shares on Jan 1
        BuySellEntry buy1 = new BuySellEntry();
        buy1.setType(PortfolioTransaction.Type.BUY);
        buy1.setDate(LocalDateTime.parse("2024-01-01T00:00:00"));
        buy1.setShares(100 * Values.Share.factor());
        buy1.setMonetaryAmount(Money.of(CurrencyUnit.USD, 15000_00));
        buy1.setSecurity(security);
        buy1.insert(account, portfolio);

        // Dividend on Jan 10
        AccountTransaction dividend = new AccountTransaction();
        dividend.setType(AccountTransaction.Type.DIVIDENDS);
        dividend.setDateTime(LocalDateTime.parse("2024-01-10T00:00:00"));
        dividend.setSecurity(security);
        dividend.setShares(0);
        dividend.setMonetaryAmount(Money.of(CurrencyUnit.USD, 100_00));

        // Buy 200 more shares on Jan 20 (AFTER dividend)
        BuySellEntry buy2 = new BuySellEntry();
        buy2.setType(PortfolioTransaction.Type.BUY);
        buy2.setDate(LocalDateTime.parse("2024-01-20T00:00:00"));
        buy2.setShares(200 * Values.Share.factor());
        buy2.setMonetaryAmount(Money.of(CurrencyUnit.USD, 30000_00));
        buy2.setSecurity(security);
        buy2.insert(account, portfolio);

        // Execute
        AutoPopulateDividendSharesAction action = new AutoPopulateDividendSharesAction(client, true,
                        Collections.emptySet());
        Status status = action.process(dividend, account);

        // Assert: Should only count 100 shares held on Jan 10, not the future 200
        assertThat(status.getCode(), is(Status.Code.OK));
        assertThat(dividend.getShares(), is(100 * Values.Share.factor()));
    }

    @Test
    public void testHoldingsAcrossMultiplePortfolios()
    {
        // Setup: Same security held in two different portfolios
        Client client = new Client();
        Security security = new Security("Google LLC", CurrencyUnit.USD);
        client.addSecurity(security);

        Account account = new Account("Brokerage Account");
        account.setCurrencyCode(CurrencyUnit.USD);
        client.addAccount(account);

        Portfolio portfolio1 = new Portfolio("Portfolio 1");
        portfolio1.setReferenceAccount(account);
        client.addPortfolio(portfolio1);

        Portfolio portfolio2 = new Portfolio("Portfolio 2");
        portfolio2.setReferenceAccount(account);
        client.addPortfolio(portfolio2);

        // Buy 100 shares in portfolio 1
        BuySellEntry buy1 = new BuySellEntry();
        buy1.setType(PortfolioTransaction.Type.BUY);
        buy1.setDate(LocalDateTime.parse("2024-01-01T00:00:00"));
        buy1.setShares(100 * Values.Share.factor());
        buy1.setMonetaryAmount(Money.of(CurrencyUnit.USD, 15000_00));
        buy1.setSecurity(security);
        buy1.insert(account, portfolio1);

        // Buy 50 shares in portfolio 2
        BuySellEntry buy2 = new BuySellEntry();
        buy2.setType(PortfolioTransaction.Type.BUY);
        buy2.setDate(LocalDateTime.parse("2024-01-05T00:00:00"));
        buy2.setShares(50 * Values.Share.factor());
        buy2.setMonetaryAmount(Money.of(CurrencyUnit.USD, 7500_00));
        buy2.setSecurity(security);
        buy2.insert(account, portfolio2);

        // Dividend on Jan 15 (should sum holdings from all portfolios)
        AccountTransaction dividend = new AccountTransaction();
        dividend.setType(AccountTransaction.Type.DIVIDENDS);
        dividend.setDateTime(LocalDateTime.parse("2024-01-15T00:00:00"));
        dividend.setSecurity(security);
        dividend.setShares(0);
        dividend.setMonetaryAmount(Money.of(CurrencyUnit.USD, 150_00));

        // Execute
        AutoPopulateDividendSharesAction action = new AutoPopulateDividendSharesAction(client, true,
                        Collections.emptySet());
        Status status = action.process(dividend, account);

        // Assert: Should calculate total of 150 shares across all portfolios
        assertThat(status.getCode(), is(Status.Code.OK));
        assertThat(dividend.getShares(), is(150 * Values.Share.factor()));
    }

    @Test
    public void testDeliveryInboundAndOutbound()
    {
        // Setup: Test DELIVERY_INBOUND and DELIVERY_OUTBOUND transaction types
        Client client = new Client();
        Security security = new Security("Netflix Inc.", CurrencyUnit.USD);
        client.addSecurity(security);

        Account account = new Account("Brokerage Account");
        account.setCurrencyCode(CurrencyUnit.USD);
        client.addAccount(account);

        Portfolio portfolio = new Portfolio("Stock Portfolio");
        portfolio.setReferenceAccount(account);
        client.addPortfolio(portfolio);

        // Delivery inbound of 150 shares on Jan 1
        PortfolioTransaction delivery = new PortfolioTransaction();
        delivery.setType(PortfolioTransaction.Type.DELIVERY_INBOUND);
        delivery.setDateTime(LocalDateTime.parse("2024-01-01T00:00:00"));
        delivery.setShares(150 * Values.Share.factor());
        delivery.setMonetaryAmount(Money.of(CurrencyUnit.USD, 22500_00));
        delivery.setSecurity(security);
        portfolio.addTransaction(delivery);

        // Delivery outbound of 30 shares on Jan 5
        PortfolioTransaction deliveryOut = new PortfolioTransaction();
        deliveryOut.setType(PortfolioTransaction.Type.DELIVERY_OUTBOUND);
        deliveryOut.setDateTime(LocalDateTime.parse("2024-01-05T00:00:00"));
        deliveryOut.setShares(30 * Values.Share.factor());
        deliveryOut.setMonetaryAmount(Money.of(CurrencyUnit.USD, 4500_00));
        deliveryOut.setSecurity(security);
        portfolio.addTransaction(deliveryOut);

        // Dividend on Jan 15 (should have 120 shares)
        AccountTransaction dividend = new AccountTransaction();
        dividend.setType(AccountTransaction.Type.DIVIDENDS);
        dividend.setDateTime(LocalDateTime.parse("2024-01-15T00:00:00"));
        dividend.setSecurity(security);
        dividend.setShares(0);
        dividend.setMonetaryAmount(Money.of(CurrencyUnit.USD, 120_00));

        // Execute
        AutoPopulateDividendSharesAction action = new AutoPopulateDividendSharesAction(client, true,
                        Collections.emptySet());
        Status status = action.process(dividend, account);

        // Assert
        assertThat(status.getCode(), is(Status.Code.OK));
        assertThat(dividend.getShares(), is(120 * Values.Share.factor()));
    }

    @Test
    public void testActionDisabled()
    {
        // Setup: Create a client with holdings
        Client client = new Client();
        Security security = new Security("Apple Inc.", CurrencyUnit.USD);
        client.addSecurity(security);

        Account account = new Account("Brokerage Account");
        account.setCurrencyCode(CurrencyUnit.USD);
        client.addAccount(account);

        Portfolio portfolio = new Portfolio("Stock Portfolio");
        portfolio.setReferenceAccount(account);
        client.addPortfolio(portfolio);

        // Buy 100 shares
        BuySellEntry buy = new BuySellEntry();
        buy.setType(PortfolioTransaction.Type.BUY);
        buy.setDate(LocalDateTime.parse("2024-01-01T00:00:00"));
        buy.setShares(100 * Values.Share.factor());
        buy.setMonetaryAmount(Money.of(CurrencyUnit.USD, 15000_00));
        buy.setSecurity(security);
        buy.insert(account, portfolio);

        // Create dividend
        AccountTransaction dividend = new AccountTransaction();
        dividend.setType(AccountTransaction.Type.DIVIDENDS);
        dividend.setDateTime(LocalDateTime.parse("2024-01-15T00:00:00"));
        dividend.setSecurity(security);
        dividend.setShares(0);
        dividend.setMonetaryAmount(Money.of(CurrencyUnit.USD, 100_00));

        // Execute with action DISABLED
        AutoPopulateDividendSharesAction action = new AutoPopulateDividendSharesAction(client, false,
                        Collections.emptySet());
        Status status = action.process(dividend, account);

        // Assert: Shares should remain 0 because action is disabled
        assertThat(status.getCode(), is(Status.Code.OK));
        assertThat(dividend.getShares(), is(0L));
    }

    @Test
    public void testExcludeTransactionsFromCurrentImport()
    {
        // Setup: Test that buy transactions from the same import are excluded
        Client client = new Client();
        Security security = new Security("Microsoft Corp.", CurrencyUnit.USD);
        client.addSecurity(security);

        Account account = new Account("Brokerage Account");
        account.setCurrencyCode(CurrencyUnit.USD);
        client.addAccount(account);

        Portfolio portfolio = new Portfolio("Stock Portfolio");
        portfolio.setReferenceAccount(account);
        client.addPortfolio(portfolio);

        // Existing holding: 50 shares on Jan 1
        BuySellEntry existingBuy = new BuySellEntry();
        existingBuy.setType(PortfolioTransaction.Type.BUY);
        existingBuy.setDate(LocalDateTime.parse("2024-01-01T00:00:00"));
        existingBuy.setShares(50 * Values.Share.factor());
        existingBuy.setMonetaryAmount(Money.of(CurrencyUnit.USD, 5000_00));
        existingBuy.setSecurity(security);
        existingBuy.insert(account, portfolio);

        // Import batch contains: buy of 100 shares on Jan 10 and dividend on Jan 15
        BuySellEntry importedBuy = new BuySellEntry();
        importedBuy.setType(PortfolioTransaction.Type.BUY);
        importedBuy.setDate(LocalDateTime.parse("2024-01-10T00:00:00"));
        importedBuy.setShares(100 * Values.Share.factor());
        importedBuy.setMonetaryAmount(Money.of(CurrencyUnit.USD, 10000_00));
        importedBuy.setSecurity(security);
        // Note: NOT inserted into portfolio yet - this simulates being in the import

        AccountTransaction dividend = new AccountTransaction();
        dividend.setType(AccountTransaction.Type.DIVIDENDS);
        dividend.setDateTime(LocalDateTime.parse("2024-01-15T00:00:00"));
        dividend.setSecurity(security);
        dividend.setShares(0);
        dividend.setMonetaryAmount(Money.of(CurrencyUnit.USD, 150_00));

        // Create the exclusion set with the imported buy transaction
        var transactionsToExclude = Collections.singleton(importedBuy.getPortfolioTransaction());

        // Execute
        AutoPopulateDividendSharesAction action = new AutoPopulateDividendSharesAction(client, true,
                        transactionsToExclude);
        Status status = action.process(dividend, account);

        // Assert: Should only count the 50 existing shares, not the 100 shares from current import
        assertThat(status.getCode(), is(Status.Code.OK));
        assertThat(dividend.getShares(), is(50 * Values.Share.factor()));
    }
}
