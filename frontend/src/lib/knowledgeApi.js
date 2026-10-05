import { availableSlotsPath } from './bookingConversation.js';

const configuredBaseUrl = import.meta.env?.VITE_API_BASE_URL || '';
export const DEFAULT_PRODUCTION_API_BASE_URL = 'https://molarai.onrender.com';

// In development, Vite proxies /api to VITE_API_BASE_URL. In production, the same
// variable is used as the direct backend origin. The production fallback keeps a
// build functional if Vercel's variable was omitted; VITE_API_BASE_URL takes precedence.
export function apiUrl(path, { development = !Boolean(import.meta.env?.PROD), baseUrl = configuredBaseUrl } = {}) {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`;
  if (development) return normalizedPath;
  const origin = (baseUrl || DEFAULT_PRODUCTION_API_BASE_URL).replace(/\/+$/, '');
  return `${origin}${normalizedPath}`;
}

export class KnowledgeUnavailableError extends Error {
  constructor() {
    super('Knowledge-based answers are temporarily unavailable.');
    this.name = 'KnowledgeUnavailableError';
  }
}

export async function askMolarAI(query) {
  const response = await fetch(apiUrl('/api/knowledge/answer'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query }),
  });

  if (!response.ok) {
    if (response.status === 503) {
      const error = await response.json();
      if (error?.code === 'KNOWLEDGE_UNAVAILABLE') throw new KnowledgeUnavailableError();
    }
    throw new Error(`MolarAI request failed (${response.status})`);
  }

  const data = await response.json();
  if (typeof data.answer !== 'string') {
    throw new Error('MolarAI returned an invalid answer');
  }

  return {
    answer: data.answer,
    sources: Array.isArray(data.sources) ? data.sources : [],
    answerSource: typeof data.answerSource === 'string' ? data.answerSource : 'knowledge_base',
  };
}

export async function fetchAvailableSlots(date) {
  if (typeof date !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
    throw new Error('Appointment date must use YYYY-MM-DD');
  }

  const response = await fetch(apiUrl(availableSlotsPath(date)));
  if (!response.ok) {
    throw new Error(`Appointment slots request failed (${response.status})`);
  }

  const data = await response.json();
  if (!Array.isArray(data.slots)) {
    throw new Error('Appointment slots response was invalid');
  }

  return {
    availability: data.availability,
    slots: data.slots.map(toSlotSummary).filter((slot) => slot.status === 'AVAILABLE'),
  };
}

export async function fetchAppointmentSlot(slotId) {
  const response = await fetch(apiUrl(`/api/appointments/slots/${encodeURIComponent(slotId)}`));
  if (!response.ok) throw new AppointmentApiError(response.status);
  return toSlotSummary(await response.json());
}

export async function findCancellationMatches(patientName, patientContact) {
  const response = await fetch(apiUrl('/api/appointments/cancellation-matches'), {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ patientName, patientContact }),
  });
  if (!response.ok) throw new AppointmentApiError(response.status);
  const data = await response.json();
  if (!Array.isArray(data.slots)) throw new Error('Cancellation response was invalid');
  return data.slots.map(toSlotSummary);
}

export async function cancelAppointment(slotId) {
  const response = await fetch(apiUrl(`/api/appointments/slots/${encodeURIComponent(slotId)}/booking`), {
    method: 'DELETE',
  });
  if (!response.ok) throw new AppointmentApiError(response.status);
  return toSlotSummary(await response.json());
}

export async function requestCancellationOtp({ appointmentSlotId, patientName, patientContact }) {
  const response = await fetch(apiUrl('/api/appointments/cancellation-requests'), {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ appointmentSlotId, patientName, patientContact }),
  });
  if (!response.ok) throw new AppointmentApiError(response.status);
  return await response.json();
}

export async function resendCancellationOtp(requestId) {
  const response = await fetch(apiUrl(`/api/appointments/cancellation-requests/${encodeURIComponent(requestId)}/resend`), { method: 'POST' });
  if (!response.ok) throw new AppointmentApiError(response.status);
  return await response.json();
}

export async function verifyCancellationOtp(requestId, otp) {
  const response = await fetch(apiUrl(`/api/appointments/cancellation-requests/${encodeURIComponent(requestId)}/verify`), {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ otp }),
  });
  if (!response.ok) throw new AppointmentApiError(response.status);
  return await response.json();
}

export async function cancelVerifiedAppointment(requestId) {
  const response = await fetch(apiUrl(`/api/appointments/cancellation-requests/${encodeURIComponent(requestId)}`), { method: 'DELETE' });
  if (!response.ok) throw new AppointmentApiError(response.status);
  return toSlotSummary(await response.json());
}

export async function rescheduleAppointment(slotId, newSlotId) {
  const response = await fetch(apiUrl(`/api/appointments/slots/${encodeURIComponent(slotId)}/reschedule`), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ newSlotId }),
  });
  if (!response.ok) throw new AppointmentApiError(response.status);
  return toSlotSummary(await response.json());
}

export async function bookAppointment({ slotId, patientName, patientContact }) {
  const response = await fetch(apiUrl('/api/appointments/book'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ slotId, patientName, patientContact }),
  });

  if (!response.ok) {
    throw new AppointmentApiError(response.status);
  }

  return toSlotSummary(await response.json());
}

export class AppointmentApiError extends Error {
  constructor(status) {
    super(`Appointment request failed (${status})`);
    this.name = 'AppointmentApiError';
    this.status = status;
  }
}

function toSlotSummary(slot) {
  return {
    id: slot.id,
    date: slot.date,
    startTime: slot.startTime,
    endTime: slot.endTime,
    status: slot.status,
    provider: slot.provider ?? null,
  };
}
