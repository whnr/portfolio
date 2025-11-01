package name.abuchen.portfolio.datatransfer.actions;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import name.abuchen.portfolio.datatransfer.ImportAction;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.snapshot.PortfolioSnapshot;
import name.abuchen.portfolio.snapshot.SecurityPosition;

/**
 * Automatically populates the number of shares for dividend transactions that
 * have zero shares set. This action calculates the shares held in portfolios
 * associated with the account on the dividend date.
 * <p>
 * This is particularly useful for US dividend imports where brokers often do
 * not include the share quantity in dividend statements.
 * </p>
 * <p>
 * The calculation only considers:
 * <ul>
 * <li>Portfolios that reference the same account as the dividend</li>
 * <li>Transactions that occurred before the dividend date</li>
 * <li>Existing transactions (excludes transactions from the current
 * import)</li>
 * </ul>
 * </p>
 */
public class AutoPopulateDividendSharesAction implements ImportAction
{
    private final Client client;
    private final boolean isEnabled;
    private final Set<Transaction> transactionsToExclude;

    /**
     * Creates a new action instance.
     *
     * @param client
     *            the client containing portfolio data
     * @param isEnabled
     *            whether this action should process transactions
     * @param transactionsToExclude
     *            transactions being imported (should be excluded from
     *            calculation)
     */
    public AutoPopulateDividendSharesAction(Client client, boolean isEnabled,
                    Set<Transaction> transactionsToExclude)
    {
        this.client = client;
        this.isEnabled = isEnabled;
        this.transactionsToExclude = transactionsToExclude;
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
     * Excludes transactions from the current import.
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

        // Create a currency converter for the calculation
        CurrencyConverter converter = new CurrencyConverterImpl(ExchangeRateProviderFactory.getInstance(),
                        client.getBaseCurrency());

        // Calculate total shares across all relevant portfolios
        long totalShares = 0;

        for (Portfolio portfolio : relevantPortfolios)
        {
            // Get transactions for this security in this portfolio, excluding
            // imported ones
            List<PortfolioTransaction> existingTransactions = portfolio.getTransactions().stream()
                            .filter(t -> security.equals(t.getSecurity()))
                            .filter(t -> !t.getDateTime().toLocalDate().isAfter(date))
                            .filter(t -> !transactionsToExclude.contains(t)).collect(Collectors.toList());

            if (!existingTransactions.isEmpty())
            {
                // Use PortfolioSnapshot to calculate shares
                // We need to create a temporary portfolio with only existing
                // transactions
                Portfolio tempPortfolio = new Portfolio();
                tempPortfolio.setName(portfolio.getName());
                tempPortfolio.setReferenceAccount(portfolio.getReferenceAccount());
                existingTransactions.forEach(tempPortfolio::addTransaction);

                PortfolioSnapshot snapshot = PortfolioSnapshot.create(tempPortfolio, converter, date);
                SecurityPosition position = snapshot.getPositionsBySecurity().get(security);

                if (position != null)
                    totalShares += position.getShares();
            }
        }

        return totalShares;
    }
}
