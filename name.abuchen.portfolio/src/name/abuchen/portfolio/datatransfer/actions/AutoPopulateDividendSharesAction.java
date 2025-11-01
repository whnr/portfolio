package name.abuchen.portfolio.datatransfer.actions;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import name.abuchen.portfolio.datatransfer.Extractor;
import name.abuchen.portfolio.datatransfer.ImportAction;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.PortfolioTransferEntry;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.snapshot.PortfolioSnapshot;
import name.abuchen.portfolio.snapshot.SecurityPosition;
import name.abuchen.portfolio.ui.wizards.datatransfer.ReviewExtractedItemsPage.ExtractedEntry;

/**
 * Automatically populates the number of shares for dividend transactions that
 * have zero shares set. This action calculates the shares held in portfolios
 * associated with the account on the dividend date.
 * <p>
 * This is particularly useful for US dividend imports where brokers often do
 * not include the share quantity in dividend statements.
 * </p>
 * <p>
 * The calculation considers:
 * <ul>
 * <li>Portfolios that reference the same account as the dividend</li>
 * <li>Transactions that occurred before or on the dividend date</li>
 * <li>Both existing transactions AND transactions from the current import
 * (e.g., if importing a buy on Jan 10 and dividend on Jan 15, the shares from
 * the buy are counted)</li>
 * </ul>
 * </p>
 * <p>
 * Note: Since the exact ex-dividend date is unknown, this uses the dividend
 * payment date as an approximation. This is opt-in to give users control.
 * </p>
 */
public class AutoPopulateDividendSharesAction implements ImportAction
{
    private final Client client;
    private final boolean isEnabled;
    private final List<ExtractedEntry> currentImportEntries;

    /**
     * Creates a new action instance.
     *
     * @param client
     *            the client containing portfolio data
     * @param isEnabled
     *            whether this action should process transactions
     * @param currentImportEntries
     *            entries from the current import batch (to include buy/sell
     *            transactions that affect share counts)
     */
    public AutoPopulateDividendSharesAction(Client client, boolean isEnabled,
                    List<ExtractedEntry> currentImportEntries)
    {
        this.client = client;
        this.isEnabled = isEnabled;
        this.currentImportEntries = currentImportEntries != null ? currentImportEntries : new ArrayList<>();
    }

    @Override
    public Status process(AccountTransaction transaction, Account account)
    {
        // Check if action is enabled
        if (!isEnabled)
            return Status.OK_STATUS;

        // Only process dividend transactions
        if (transaction.getType() != AccountTransaction.Type.DIVIDENDS)
            return Status.OK_STATUS;

        // Only populate if shares are not already set
        if (transaction.getShares() != 0)
            return Status.OK_STATUS;

        // Security is required to look up holdings
        Security security = transaction.getSecurity();
        if (security == null)
            return Status.OK_STATUS;

        // Calculate shares held on the dividend date
        LocalDate dividendDate = transaction.getDateTime().toLocalDate();
        long sharesHeld = calculateSharesHeldInAccount(client, account, security, dividendDate);

        // Set the shares on the transaction
        transaction.setShares(sharesHeld);

        return Status.OK_STATUS;
    }

    /**
     * Calculates the total number of shares held for a given security on a
     * specific date within portfolios that reference the specified account.
     * Includes both existing transactions and relevant transactions from the
     * current import.
     *
     * @param client
     *            the client containing all portfolio and transaction data
     * @param account
     *            the account to find associated portfolios for
     * @param security
     *            the security to calculate holdings for
     * @param date
     *            the date to calculate holdings as of
     * @return the total number of shares held (in Values.Share.factor()
     *         precision)
     */
    private long calculateSharesHeldInAccount(Client client, Account account, Security security, LocalDate date)
    {
        // Find all portfolios that reference this account
        List<Portfolio> relevantPortfolios = client.getPortfolios().stream()
                        .filter(p -> account.equals(p.getReferenceAccount())).collect(Collectors.toList());

        if (relevantPortfolios.isEmpty())
            return 0L;

        // Extract portfolio transactions from current import that should be included
        List<PortfolioTransaction> importedTransactions = extractRelevantImportedTransactions(account, security, date);

        // Create a currency converter for the calculation
        CurrencyConverter converter = new CurrencyConverterImpl(ExchangeRateProviderFactory.getInstance(),
                        client.getBaseCurrency());

        // Calculate total shares across all relevant portfolios
        long totalShares = 0;

        for (Portfolio portfolio : relevantPortfolios)
        {
            // Get existing transactions for this security in this portfolio
            List<PortfolioTransaction> existingTransactions = portfolio.getTransactions().stream()
                            .filter(t -> security.equals(t.getSecurity()))
                            .filter(t -> !t.getDateTime().toLocalDate().isAfter(date))
                            .collect(Collectors.toList());

            // Get imported transactions for this specific portfolio
            List<PortfolioTransaction> importedForPortfolio = importedTransactions.stream()
                            .filter(t -> belongsToPortfolio(t, portfolio)).collect(Collectors.toList());

            // Combine existing and imported transactions
            if (!existingTransactions.isEmpty() || !importedForPortfolio.isEmpty())
            {
                Portfolio tempPortfolio = new Portfolio();
                tempPortfolio.setName(portfolio.getName());
                tempPortfolio.setReferenceAccount(portfolio.getReferenceAccount());
                existingTransactions.forEach(tempPortfolio::addTransaction);
                importedForPortfolio.forEach(tempPortfolio::addTransaction);

                PortfolioSnapshot snapshot = PortfolioSnapshot.create(tempPortfolio, converter, date);
                SecurityPosition position = snapshot.getPositionsBySecurity().get(security);

                if (position != null)
                    totalShares += position.getShares();
            }
        }

        return totalShares;
    }

    /**
     * Extracts portfolio transactions from the current import that are
     * relevant for calculating share holdings.
     */
    private List<PortfolioTransaction> extractRelevantImportedTransactions(Account account, Security security,
                    LocalDate date)
    {
        List<PortfolioTransaction> transactions = new ArrayList<>();

        for (ExtractedEntry entry : currentImportEntries)
        {
            Extractor.Item item = entry.getItem();

            // Extract from BuySellEntry items
            if (item instanceof Extractor.BuySellEntryItem bsItem)
            {
                BuySellEntry buySell = (BuySellEntry) bsItem.getSubject();
                PortfolioTransaction pt = buySell.getPortfolioTransaction();

                // Check if this transaction is for the right security, account, and date
                if (security.equals(pt.getSecurity()) && !pt.getDateTime().toLocalDate().isAfter(date)
                                && accountMatches(buySell.getAccount(), account))
                {
                    transactions.add(pt);
                }
            }
            // Extract from PortfolioTransferEntry items
            else if (item instanceof Extractor.PortfolioTransferItem ptItem)
            {
                PortfolioTransferEntry transfer = (PortfolioTransferEntry) ptItem.getSubject();
                PortfolioTransaction source = transfer.getSourceTransaction();
                PortfolioTransaction target = transfer.getTargetTransaction();

                if (security.equals(source.getSecurity()) && !source.getDateTime().toLocalDate().isAfter(date))
                    transactions.add(source);

                if (security.equals(target.getSecurity()) && !target.getDateTime().toLocalDate().isAfter(date))
                    transactions.add(target);
            }
        }

        return transactions;
    }

    /**
     * Checks if an account matches (handles null)
     */
    private boolean accountMatches(Account acc1, Account acc2)
    {
        if (acc1 == null || acc2 == null)
            return false;
        return acc1.equals(acc2);
    }

    /**
     * Determines if a portfolio transaction belongs to a specific portfolio.
     * Since transactions in the import aren't yet assigned to portfolios, we
     * use the reference account to determine association.
     */
    private boolean belongsToPortfolio(PortfolioTransaction transaction, Portfolio portfolio)
    {
        // During import, we can't directly check portfolio assignment
        // The transaction will be added to a portfolio that references the same account
        // For now, we include all transactions and let the account matching handle it
        return true;
    }
}
