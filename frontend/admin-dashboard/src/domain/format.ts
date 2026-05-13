import type { Numeric } from '../api/types';

export function numberValue(value: Numeric | undefined): number | null {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  const parsed = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

export function formatNumber(value: Numeric | undefined, digits = 2): string {
  const parsed = numberValue(value);
  if (parsed === null) {
    return '-';
  }
  return new Intl.NumberFormat('ko-KR', {
    maximumFractionDigits: digits,
    minimumFractionDigits: digits
  }).format(parsed);
}

export function formatUsd(value: Numeric | undefined): string {
  const parsed = numberValue(value);
  if (parsed === null) {
    return '-';
  }
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    maximumFractionDigits: 2
  }).format(parsed);
}

export function formatKrw(value: Numeric | undefined): string {
  const parsed = numberValue(value);
  if (parsed === null) {
    return '-';
  }
  return new Intl.NumberFormat('ko-KR', {
    style: 'currency',
    currency: 'KRW',
    maximumFractionDigits: 0
  }).format(parsed);
}

export function formatDateTime(value: string | null | undefined, timeZone = 'Asia/Seoul'): string {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone
  }).format(date);
}
