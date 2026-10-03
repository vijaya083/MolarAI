export function isCancellationEnabled(value = import.meta.env?.VITE_CANCELLATION_ENABLED) {
  return value !== 'false';
}
