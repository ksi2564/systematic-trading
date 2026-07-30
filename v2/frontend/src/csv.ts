import { type MarketBarInput } from './api';

export function parseMarketBarsCsv(source: string): MarketBarInput[] {
  const lines = source
    .replace(/^\uFEFF/, '')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);
  if (lines.length < 2) {
    throw new Error('CSV 헤더와 한 행 이상의 가격 데이터가 필요합니다.');
  }
  const header = lines[0].split(',').map((value) => value.trim().toLowerCase());
  const dateColumn = header.includes('trading_date') ? 'trading_date' : 'date';
  const required = [dateColumn, 'symbol', 'open', 'high', 'low', 'close', 'volume'];
  const missing = required.filter((name) => !header.includes(name));
  if (missing.length) {
    throw new Error(`CSV 필수 열이 없습니다: ${missing.join(', ')}`);
  }
  const index = Object.fromEntries(header.map((name, position) => [name, position]));
  const optional = (values: string[], name: string) => {
    const position = index[name];
    return position === undefined || !values[position]?.trim() ? null : values[position].trim();
  };
  return lines.slice(1).map((line, offset) => {
    const values = line.split(',');
    const lineNumber = offset + 2;
    if (values.length !== header.length) {
      throw new Error(`CSV ${lineNumber}행의 열 개수가 헤더와 다릅니다.`);
    }
    const tradingDate = values[index[dateColumn]]?.trim();
    const symbol = values[index.symbol]?.trim().toUpperCase();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(tradingDate)) {
      throw new Error(`CSV ${lineNumber}행의 날짜는 YYYY-MM-DD 형식이어야 합니다.`);
    }
    if (!symbol) {
      throw new Error(`CSV ${lineNumber}행의 종목 코드가 비어 있습니다.`);
    }
    for (const name of ['open', 'high', 'low', 'close', 'volume']) {
      const value = values[index[name]]?.trim();
      if (!value || !Number.isFinite(Number(value))) {
        throw new Error(`CSV ${lineNumber}행의 ${name} 값이 숫자가 아닙니다.`);
      }
    }
    return {
      trading_date: tradingDate,
      observed_at: optional(values, 'observed_at'),
      available_at: optional(values, 'available_at'),
      symbol,
      open: values[index.open].trim(),
      high: values[index.high].trim(),
      low: values[index.low].trim(),
      close: values[index.close].trim(),
      volume: values[index.volume].trim(),
      provider: optional(values, 'provider') ?? 'csv-upload',
      official: optional(values, 'official')?.toLowerCase() === 'true'
    };
  });
}
