package name.abuchen.portfolio.datatransfer.actions;

import java.time.LocalDate;
import java.util.Map;

import name.abuchen.portfolio.datatransfer.ImportAction;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.snapshot.SecurityPosition;

/**
 * Automatically populates the number of shares for dividend transactions that
 * have zero shares set. This action calculates the total shares held across
 * all portfolios on the dividend date and assigns that value to the
 * transaction.
 * <p>
 * This is particularly useful for US dividend imports where brokers often do
 * not include the share quantity in dividend statements.
 * </p>
 */
public class AutoPopulateDividendSharesAction implements ImportAction
{
    private final Client client;

    public AutoPopulateDividendSharesAction(Client client)
    {
        this.client = client;
    }

    @Override
    public Status process(AccountTransaction transaction, Account account)
    {
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
        long sharesHeld = calculateSharesHeld(client, security, dividendDate);

        // Set the shares on the transaction
        transaction.setShares(sharesHeld);

        return Status.OK_STATUS;
    }

    /**
     * Calculates the total number of shares held for a given security on a
     * specific date across all portfolios in the client.
     *
     * @param client
     *            the client containing all portfolio and transaction data
     * @param security
     *            the security to calculate holdings for
     * @param date
     *            the date to calculate holdings as of
     * @return the total number of shares held (in Values.Share.factor()
     *         precision)
     */
    private long calculateSharesHeld(Client client, Security security, LocalDate date)
    {
        // Create a currency converter for the calculation
        // We use the client's base currency as the term currency
        CurrencyConverter converter = new CurrencyConverterImpl(ExchangeRateProviderFactory.getInstance(),
                        client.getBaseCurrency());

        // Create a snapshot of the client's portfolio positions on the given date
        ClientSnapshot snapshot = ClientSnapshot.create(client, converter, date);

        // Get the position map which contains all security positions
        Map<Security, SecurityPosition> positions = snapshot.getJointPortfolio().getPositionsBySecurity();

        // Look up the position for our security
        SecurityPosition position = positions.get(security);

        // Return the shares held, or 0 if no position exists
        return position != null ? position.getShares() : 0L;
    }
}
