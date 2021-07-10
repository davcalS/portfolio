package name.abuchen.portfolio.online.impl;

import java.io.IOException;
import java.net.URISyntaxException;
import java.text.MessageFormat;
import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import name.abuchen.portfolio.Messages;
import name.abuchen.portfolio.PortfolioLog;
import name.abuchen.portfolio.model.LatestSecurityPrice;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.online.QuoteFeed;
import name.abuchen.portfolio.online.QuoteFeedData;
import name.abuchen.portfolio.util.WebAccess;

public final class CoinGeckoQuoteFeed implements QuoteFeed
{
    public static final String ID = "COINGECKO"; //$NON-NLS-1$

    private HashMap<String, String> tickerIdMap;

    @Override
    public String getId()
    {
        return ID;
    }

    @Override
    public String getName()
    {
        return "CoinGecko"; //$NON-NLS-1$
    }

    @Override
    public Optional<LatestSecurityPrice> getLatestQuote(Security security)
    {
        QuoteFeedData data = getHistoricalQuotes(security, false, LocalDate.now());

        if (!data.getErrors().isEmpty())
            PortfolioLog.error(data.getErrors());

        List<LatestSecurityPrice> prices = data.getLatestPrices();

        if (prices.isEmpty())
            return Optional.empty();

        Collections.sort(prices, new SecurityPrice.ByDate());

        return Optional.of(prices.get(prices.size() - 1));
    }

    @Override
    public QuoteFeedData getHistoricalQuotes(Security security, boolean collectRawResponse)
    {
        LocalDate quoteStartDate = LocalDate.of(1970, 01, 01);

        if (!security.getPrices().isEmpty())
            quoteStartDate = security.getPrices().get(security.getPrices().size() - 1).getDate();

        return getHistoricalQuotes(security, collectRawResponse, quoteStartDate);
    }

    @Override
    public QuoteFeedData previewHistoricalQuotes(Security security)
    {
        return getHistoricalQuotes(security, true, LocalDate.now().minusMonths(2));
    }

    private void convertCoinGeckoJsonArray(JSONArray ohlcArray, QuoteFeedData data)
    {
        if (ohlcArray.isEmpty())
            return;

        List<LatestSecurityPrice> prices = new ArrayList<>();

        for (Object price : ohlcArray)
        {
            if (price instanceof JSONArray)
            {
                try
                {
                    JSONArray priceArray = (JSONArray) price;
                    prices.add(fromArray(priceArray));
                }
                catch (ParseException ex)
                {
                    data.addError(ex);
                }
            }
        }

        data.addAllPrices(prices);
    }

    private LatestSecurityPrice fromArray(JSONArray ohlcArray) throws ParseException
    {
        long timestamp = Long.parseLong(ohlcArray.get(0).toString());
        long close = ohlcArray.get(1) != null ? YahooHelper.asPrice(ohlcArray.get(1).toString()) : 0;

        ZonedDateTime date = ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC);

        LatestSecurityPrice price = new LatestSecurityPrice();
        price.setValue(close);

        // Closing prices will be returned with time 00:00:00 of the next day
        if (date.getHour() == 0 && date.getMinute() == 0 && date.getSecond() == 0)
            price.setDate(LocalDate.of(date.getYear(), date.getMonth(), date.getDayOfMonth()).minusDays(1));
        else
            price.setDate(LocalDate.of(date.getYear(), date.getMonth(), date.getDayOfMonth()));

        return price;
    }

    private QuoteFeedData getHistoricalQuotes(Security security, boolean collectRawResponse, LocalDate start)
    {
        if (security.getTickerSymbol() == null)
            return QuoteFeedData.withError(
                            new IOException(MessageFormat.format(Messages.MsgMissingTickerSymbol, security.getName())));

        QuoteFeedData data = new QuoteFeedData();

        try
        {
            String coinGeckoId = getCoinGeckoIdForTicker(security.getTickerSymbol().toLowerCase());
            String endpoint = "/api/v3/coins/" + coinGeckoId + "/market_chart"; //$NON-NLS-1$ //$NON-NLS-2$
            long days = ChronoUnit.DAYS.between(start, LocalDate.now()) + 1;

            WebAccess webaccess = new WebAccess("api.coingecko.com", endpoint) //$NON-NLS-1$
                            .addParameter("vs_currency", security.getCurrencyCode()) //$NON-NLS-1$
                            .addParameter("days", Long.toString(days)) //$NON-NLS-1$
                            .addParameter("interval", "daily"); //$NON-NLS-1$ //$NON-NLS-2$
            String html = webaccess.get();

            if (collectRawResponse)
                data.addResponse(webaccess.getURL(), html);

            JSONObject marketChartObject = (JSONObject) JSONValue.parse(html);

            if (marketChartObject != null && marketChartObject.containsKey("prices")) //$NON-NLS-1$
            {
                JSONArray priceArray = (JSONArray) marketChartObject.get("prices"); //$NON-NLS-1$
                convertCoinGeckoJsonArray(priceArray, data);
            }
        }
        catch (IOException | URISyntaxException e)
        {
            data.addError(e);
        }

        return data;
    }

    /**
     * Provide the internal CoinGecko ID for an "official" cryptocurrency ticker symbol
     * @param tickerSymbol Cryptocurrency ticker symbol
     * @return Internal CoinGecko ID for use in further API calls
     * @throws IOException No mapping found for ticker
     */
    private String getCoinGeckoIdForTicker(String tickerSymbol) throws IOException
    {
        HashMap<String, String> tickerIdMap = getTickerIdMap();
        
        if (tickerIdMap.containsKey(tickerSymbol))
            return tickerIdMap.get(tickerSymbol);
        else
            throw new IOException(MessageFormat.format(Messages.MsgMissingTickerSymbol, tickerSymbol));
    }

    /**
     * The CoinGecko API only allows to fetch a complete set of ID mappings for all coins existing on the platform.
     * In order to avoid unnecessary calls the mapping will be buffered locally in a HashMap.
     * @return Buffered HashMap for: Crypto Ticker Symbol -> Internal CoinGecko ID
     * @throws IOException Error during creation of HashMap
     */
    private synchronized HashMap<String, String> getTickerIdMap() throws IOException
    {
        if (tickerIdMap == null)
        {
            tickerIdMap = new HashMap<String, String>(10000);

            WebAccess webaccess = new WebAccess("api.coingecko.com", "/api/v3/coins/list"); //$NON-NLS-1$ //$NON-NLS-2$
            String html = webaccess.get();

            JSONArray coinArray = (JSONArray) JSONValue.parse(html);

            if (coinArray != null)
            {
                for (Object coin : coinArray)
                {
                    JSONObject coinObject = (JSONObject) coin;
                    tickerIdMap.put(coinObject.get("symbol").toString(), coinObject.get("id").toString()); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }

        return tickerIdMap;
    }
}
