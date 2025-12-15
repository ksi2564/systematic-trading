package my.side.trading.kis.dto;

public record OverseasRealtimeQuote(
        String realtimeSymbol,      // RSYM
        String symbol,              // SYMB
        int decimalPlaces,          // ZDIV
        String localDate,           // XYMD
        String localTime,           // XHMS
        String krDate,              // KYMD
        String krTime,              // KHMS
        long totalBidVolume,        // BVOL
        long totalAskVolume,        // AVOL
        long totalBidVolumeChange,  // BDVL
        long totalAskVolumeChange,  // ADVL
        double bidPrice1,           // PBID1
        double askPrice1,           // PASK1
        long bidVolume1,            // VBID1
        long askVolume1,            // VASK1
        long bidVolumeChange1,      // DBID1
        long askVolumeChange1       // DASK1
) {}
