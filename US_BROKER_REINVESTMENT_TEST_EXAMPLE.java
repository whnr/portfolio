package name.abuchen.portfolio.datatransfer.pdf.usbroker;

import static name.abuchen.portfolio.datatransfer.ExtractorMatchers.dividend;
import static name.abuchen.portfolio.datatransfer.ExtractorMatchers.hasAmount;
import static name.abuchen.portfolio.datatransfer.ExtractorMatchers.hasDate;
import static name.abuchen.portfolio.datatransfer.ExtractorMatchers.hasNote;
import static name.abuchen.portfolio.datatransfer.ExtractorMatchers.hasSecurity;
import static name.abuchen.portfolio.datatransfer.ExtractorMatchers.hasShares;
import static name.abuchen.portfolio.datatransfer.ExtractorMatchers.purchase;
import static name.abuchen.portfolio.datatransfer.ExtractorTestUtilities.countAccountTransactions;
import static name.abuchen.portfolio.datatransfer.ExtractorTestUtilities.countBuySell;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsEmptyCollection.empty;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import name.abuchen.portfolio.datatransfer.Extractor;
import name.abuchen.portfolio.datatransfer.pdf.PDFInputFile;
import name.abuchen.portfolio.datatransfer.pdf.USBrokerReinvestmentExample;
import name.abuchen.portfolio.model.Client;

/**
 * Test cases for US Broker dividend reinvestment extraction
 */
@SuppressWarnings("nls")
public class USBrokerReinvestmentExampleTest
{
    @Test
    public void testDividendReinvestment01()
    {
        var client = new Client();
        var extractor = new USBrokerReinvestmentExample(client);

        List<Exception> errors = new ArrayList<>();

        var results = extractor.extract(
            PDFInputFile.loadTestCase(getClass(), "DividendReinvest01.txt"),
            errors
        );

        assertThat(errors, empty());
        assertThat(countAccountTransactions(results), is(1L));
        assertThat(countBuySell(results), is(1L));
        assertThat(results.size(), is(2));

        // Check dividend transaction
        assertThat(results.get(0), dividend(
            hasDate("2024-01-15"),
            hasShares(150.00),  // Shares that generated the dividend
            hasAmount("USD", 450.00),
            hasSecurity("AAPL"),
            hasNote("Reinvested: 2.49 shares")));

        // Check buy transaction (created from reinvestment)
        assertThat(results.get(1), purchase(
            hasDate("2024-01-15"),
            hasShares(2.49),  // Shares purchased from reinvestment
            hasAmount("USD", 449.45),
            hasSecurity("AAPL"),
            hasNote("Auto-generated from dividend reinvestment")));
    }

    @Test
    public void testDividendReinvestmentWithoutDividendShares()
    {
        // Test case where statement doesn't include dividend shares
        // The AutoPopulateDividendSharesAction should fill this later
        var client = new Client();
        var extractor = new USBrokerReinvestmentExample(client);

        List<Exception> errors = new ArrayList<>();

        var results = extractor.extract(
            PDFInputFile.loadTestCase(getClass(), "DividendReinvest02.txt"),
            errors
        );

        assertThat(errors, empty());
        assertThat(results.size(), is(2));

        // Check dividend - shares will be 0 until AutoPopulateDividendSharesAction runs
        assertThat(results.get(0), dividend(
            hasDate("2024-01-15"),
            hasShares(0),  // Will be auto-populated by ImportAction
            hasAmount("USD", 450.00),
            hasSecurity("AAPL")));

        // Check buy transaction
        assertThat(results.get(1), purchase(
            hasDate("2024-01-15"),
            hasShares(2.49),
            hasAmount("USD", 449.45),
            hasSecurity("AAPL")));
    }

    @Test
    public void testMultipleDividendReinvestments()
    {
        // Test case with multiple securities reinvested on same statement
        var client = new Client();
        var extractor = new USBrokerReinvestmentExample(client);

        List<Exception> errors = new ArrayList<>();

        var results = extractor.extract(
            PDFInputFile.loadTestCase(getClass(), "DividendReinvest03.txt"),
            errors
        );

        assertThat(errors, empty());
        assertThat(countAccountTransactions(results), is(3L)); // 3 dividends
        assertThat(countBuySell(results), is(3L)); // 3 buys
        assertThat(results.size(), is(6));

        // AAPL dividend + buy
        assertThat(results.get(0), dividend(
            hasDate("2024-01-15"),
            hasShares(150.00),
            hasAmount("USD", 450.00),
            hasSecurity("AAPL")));

        assertThat(results.get(1), purchase(
            hasDate("2024-01-15"),
            hasShares(2.49),
            hasAmount("USD", 449.45),
            hasSecurity("AAPL")));

        // MSFT dividend + buy
        assertThat(results.get(2), dividend(
            hasDate("2024-01-15"),
            hasShares(75.00),
            hasAmount("USD", 187.50),
            hasSecurity("MSFT")));

        assertThat(results.get(3), purchase(
            hasDate("2024-01-15"),
            hasShares(0.52),
            hasAmount("USD", 187.20),
            hasSecurity("MSFT")));

        // GOOGL dividend + buy
        assertThat(results.get(4), dividend(
            hasDate("2024-01-15"),
            hasShares(50.00),
            hasAmount("USD", 250.00),
            hasSecurity("GOOGL")));

        assertThat(results.get(5), purchase(
            hasDate("2024-01-15"),
            hasShares(1.79),
            hasAmount("USD", 249.30),
            hasSecurity("GOOGL")));
    }
}
