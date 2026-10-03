export const BookingPhase = Object.freeze({
  IDLE: 'IDLE',
  AWAITING_BOOKING_CONSENT: 'AWAITING_BOOKING_CONSENT',
  ASKING_DATE: 'ASKING_DATE',
  SELECTING_SLOT: 'SELECTING_SLOT',
  COLLECTING_NAME: 'COLLECTING_NAME',
  COLLECTING_CONTACT: 'COLLECTING_CONTACT',
  AWAITING_CONFIRMATION: 'AWAITING_CONFIRMATION',
  BOOKING: 'BOOKING',
  BOOKED: 'BOOKED',
  AWAITING_CANCELLATION_REFERENCE: 'AWAITING_CANCELLATION_REFERENCE',
  AWAITING_CANCELLATION_NAME: 'AWAITING_CANCELLATION_NAME',
  AWAITING_CANCELLATION_CONTACT: 'AWAITING_CANCELLATION_CONTACT',
  SELECTING_CANCELLATION: 'SELECTING_CANCELLATION',
  AWAITING_CANCELLATION_OTP_REQUEST: 'AWAITING_CANCELLATION_OTP_REQUEST',
  AWAITING_CANCELLATION_OTP: 'AWAITING_CANCELLATION_OTP',
  AWAITING_CANCELLATION_FINAL_CONFIRMATION: 'AWAITING_CANCELLATION_FINAL_CONFIRMATION',
  CANCELLING: 'CANCELLING',
  AWAITING_CANCELLATION_CONFIRMATION: 'AWAITING_CANCELLATION_CONFIRMATION',
  AWAITING_RESCHEDULE_REFERENCE: 'AWAITING_RESCHEDULE_REFERENCE',
  AWAITING_RESCHEDULE_DATE: 'AWAITING_RESCHEDULE_DATE',
  AWAITING_RESCHEDULE_SLOT: 'AWAITING_RESCHEDULE_SLOT',
  AWAITING_RESCHEDULE_CONFIRMATION: 'AWAITING_RESCHEDULE_CONFIRMATION',
  ERROR: 'ERROR',
});

export const BOOKING_OFFER = 'I can help you book an appointment. Would you like to continue?';
export const ASK_DATE_PROMPT = 'Sure! Which date would you prefer?';

const ACTIVE_PHASES = new Set([
  BookingPhase.AWAITING_BOOKING_CONSENT,
  BookingPhase.ASKING_DATE,
  BookingPhase.SELECTING_SLOT,
  BookingPhase.COLLECTING_NAME,
  BookingPhase.COLLECTING_CONTACT,
  BookingPhase.AWAITING_CONFIRMATION,
  BookingPhase.BOOKING,
  BookingPhase.AWAITING_CANCELLATION_REFERENCE,
  BookingPhase.AWAITING_CANCELLATION_NAME,
  BookingPhase.AWAITING_CANCELLATION_CONTACT,
  BookingPhase.SELECTING_CANCELLATION,
  BookingPhase.AWAITING_CANCELLATION_OTP_REQUEST,
  BookingPhase.AWAITING_CANCELLATION_OTP,
  BookingPhase.AWAITING_CANCELLATION_FINAL_CONFIRMATION,
  BookingPhase.CANCELLING,
  BookingPhase.AWAITING_CANCELLATION_CONFIRMATION,
  BookingPhase.AWAITING_RESCHEDULE_REFERENCE,
  BookingPhase.AWAITING_RESCHEDULE_DATE,
  BookingPhase.AWAITING_RESCHEDULE_SLOT,
  BookingPhase.AWAITING_RESCHEDULE_CONFIRMATION,
]);

const CANCELLATION_PHASES = new Set([
  BookingPhase.AWAITING_CANCELLATION_REFERENCE,
  BookingPhase.AWAITING_CANCELLATION_NAME,
  BookingPhase.AWAITING_CANCELLATION_CONTACT,
  BookingPhase.SELECTING_CANCELLATION,
  BookingPhase.AWAITING_CANCELLATION_OTP_REQUEST,
  BookingPhase.AWAITING_CANCELLATION_OTP,
  BookingPhase.AWAITING_CANCELLATION_FINAL_CONFIRMATION,
  BookingPhase.CANCELLING,
  BookingPhase.AWAITING_CANCELLATION_CONFIRMATION,
]);

const AFFIRMATIVE = new Set([
  'yes',
  'yeah',
  'yep',
  'sure',
  'okay',
  'ok',
  "let's do it",
  'lets do it',
  'go ahead',
  'please',
]);

const NEGATIVE = new Set([
  'no',
  'nope',
  'no thanks',
  'cancel',
  'never mind',
  'nevermind',
  'stop',
]);

const MONTHS = new Map([
  ['january', 1], ['jan', 1],
  ['february', 2], ['feb', 2],
  ['march', 3], ['mar', 3],
  ['april', 4], ['apr', 4],
  ['may', 5],
  ['june', 6], ['jun', 6],
  ['july', 7], ['jul', 7],
  ['august', 8], ['aug', 8],
  ['september', 9], ['sep', 9], ['sept', 9],
  ['october', 10], ['oct', 10],
  ['november', 11], ['nov', 11],
  ['december', 12], ['dec', 12],
]);

const ORDINAL_WORDS = new Map([
  ['first', 1], ['second', 2], ['third', 3], ['fourth', 4], ['fifth', 5],
]);

export function emptyBookingDraft() {
  return {
    slotId: null,
    date: null,
    startTime: null,
    endTime: null,
    provider: null,
    patientName: '',
    contact: '',
  };
}

export function initialBookingConversation() {
  return {
    phase: BookingPhase.IDLE,
    draft: emptyBookingDraft(),
    offeredSlots: [],
    listingId: 0,
    reply: null,
    action: null,
    slotChoices: [],
    checkedDate: null,
    checkedAvailability: null,
    purpose: null,
    existingBooking: null,
    cancellationRequestId: null,
  };
}

export function isBookingInProgress(phase) {
  return ACTIVE_PHASES.has(phase);
}

export function shouldAskKnowledge(phase) {
  return phase === BookingPhase.IDLE || phase === BookingPhase.BOOKED || phase === BookingPhase.ERROR;
}

export function offerBooking() {
  return {
    ...initialBookingConversation(),
    phase: BookingPhase.AWAITING_BOOKING_CONSENT,
    reply: BOOKING_OFFER,
  };
}

export function startBookingConversation() {
  return offerBooking();
}

export function startDateSelection() {
  return {
    ...initialBookingConversation(),
    phase: BookingPhase.ASKING_DATE,
    reply: ASK_DATE_PROMPT,
  };
}

export function routeIdleMessage(text, cancellationEnabled = true) {
  if (isNewBookingRequest(text)) return startDateSelection();
  if (isCancellationRequest(text)) {
    if (!cancellationEnabled) {
      return { ...initialBookingConversation(), reply: 'Appointment cancellation is disabled in this demo.' };
    }
    return {
      ...initialBookingConversation(),
      phase: BookingPhase.AWAITING_CANCELLATION_NAME,
      purpose: 'cancel',
      reply: 'Please enter the patient\'s registered full name.',
    };
  }
  if (isRescheduleRequest(text)) {
    return {
      ...initialBookingConversation(),
      phase: BookingPhase.AWAITING_RESCHEDULE_REFERENCE,
      purpose: 'reschedule',
      reply: 'Please enter the booking reference for the appointment you want to reschedule.',
    };
  }
  return null;
}

export function isAffirmative(text) {
  return AFFIRMATIVE.has(normalizeReply(text));
}

export function isNegative(text) {
  return NEGATIVE.has(normalizeReply(text));
}

export function handleBookingMessage(conversation, text, today = '2026-10-01', cancellationEnabled = true) {
  const message = typeof text === 'string' ? text.trim() : '';
  if (!message) {
    if (conversation.phase === BookingPhase.COLLECTING_CONTACT) {
      return stay(conversation, 'Please enter a valid 10-digit Indian mobile number, such as 98765 43210.');
    }
    if (conversation.phase === BookingPhase.AWAITING_CANCELLATION_CONTACT) {
      return stay(conversation, 'Please enter the registered 10-digit Indian mobile number.');
    }
    return stay(conversation, 'Please reply so I can continue with the booking.');
  }
  if (!cancellationEnabled && CANCELLATION_PHASES.has(conversation.phase)) {
    return { ...initialBookingConversation(), reply: 'Appointment cancellation is disabled in this demo.' };
  }
  if (isCancelCommand(conversation.phase, message)) {
    return cancelBookingDraft();
  }

  switch (conversation.phase) {
    case BookingPhase.AWAITING_BOOKING_CONSENT:
      return consent(conversation, message);
    case BookingPhase.ASKING_DATE:
      return askDate(conversation, message, today);
    case BookingPhase.SELECTING_SLOT:
      return chooseSlot(conversation, message);
    case BookingPhase.COLLECTING_NAME:
      return collectName(conversation, message);
    case BookingPhase.COLLECTING_CONTACT:
      return collectContact(conversation, message);
    case BookingPhase.AWAITING_CONFIRMATION:
      return confirm(conversation, message);
    case BookingPhase.AWAITING_CANCELLATION_REFERENCE:
    case BookingPhase.AWAITING_RESCHEDULE_REFERENCE:
      return acceptReference(conversation, message);
    case BookingPhase.AWAITING_CANCELLATION_NAME:
      return collectCancellationName(conversation, message);
    case BookingPhase.AWAITING_CANCELLATION_CONTACT:
      return collectCancellationContact(conversation, message);
    case BookingPhase.SELECTING_CANCELLATION:
      return chooseCancellation(conversation, message);
    case BookingPhase.AWAITING_CANCELLATION_OTP_REQUEST:
      return requestCancellationOtp(conversation, message);
    case BookingPhase.AWAITING_CANCELLATION_OTP:
      return enterCancellationOtp(conversation, message);
    case BookingPhase.AWAITING_CANCELLATION_FINAL_CONFIRMATION:
      return confirmVerifiedCancellation(conversation, message);
    case BookingPhase.AWAITING_CANCELLATION_CONFIRMATION:
      return confirmCancellation(conversation, message);
    case BookingPhase.AWAITING_RESCHEDULE_DATE:
      return askDate(conversation, message, today);
    case BookingPhase.AWAITING_RESCHEDULE_SLOT:
      return chooseSlot(conversation, message);
    case BookingPhase.AWAITING_RESCHEDULE_CONFIRMATION:
      return confirmReschedule(conversation, message);
    case BookingPhase.BOOKING:
      return stay(conversation, 'I am still booking that appointment. Please wait a moment.');
    default:
      return stay(conversation, null);
  }
}

export function applyAvailableSlots(conversation, lookup) {
  const date = conversation.action?.date;
  const rows = Array.isArray(lookup) ? lookup : (lookup?.slots ?? []);
  const available = rows.filter((slot) => usableSlot(slot) && slot.status !== 'BOOKED');
  const availability = Array.isArray(lookup) ? (available.length > 0 ? 'AVAILABLE' : 'NO_RECORDS') : lookup?.availability;
  if (availability === 'CLOSED') {
    return unavailableDate(conversation, date, 'CLOSED',
      `The clinic is closed on ${formatDisplayDate(date)}. Which other date would you prefer?`);
  }
  if (availability === 'OUTSIDE_BOOKING_WINDOW') {
    return unavailableDate(conversation, date, 'OUTSIDE_BOOKING_WINDOW',
      `${formatDisplayDate(date)} is outside the clinic's booking window. Please choose an earlier date.`);
  }
  if (!date || availability === 'NO_RECORDS') {
    return unavailableDate(conversation, date, 'NO_RECORDS',
      `No appointment records exist for ${formatDisplayDate(date)}, so availability cannot be confirmed. Which other date would you prefer?`);
  }
  if (availability === 'FULLY_BOOKED' || available.length === 0) {
    return unavailableDate(conversation, date, 'FULLY_BOOKED',
      `No appointments are currently available on ${formatDisplayDate(date)}. Which other date would you prefer?`);
  }
  const offeredSlots = available.map(toOfferedSlot);
  const selecting = conversation.purpose === 'reschedule'
    ? BookingPhase.AWAITING_RESCHEDULE_SLOT
    : BookingPhase.SELECTING_SLOT;
  return {
    ...base(conversation),
    phase: selecting,
    checkedDate: null,
    checkedAvailability: null,
    draft: { ...conversation.draft, date },
    offeredSlots,
    listingId: (conversation.listingId ?? 0) + 1,
    slotChoices: offeredSlots.map((slot, index) => ({ index, label: slot.label })),
    reply: `Here are the available appointments for ${formatDisplayDate(date)}.`,
  };
}

export function bookingSucceeded(conversation, bookedSlot) {
  const reference = bookedSlot?.id ? String(bookedSlot.id) : '';
  return {
    ...initialBookingConversation(),
    phase: BookingPhase.BOOKED,
    reply: reference
      ? `Your appointment is booked. Reference: ${reference}.`
      : 'Your appointment is booked.',
  };
}

export function bookingFailure(conversation, status) {
  if (status === 409 && conversation.draft?.date) {
    return {
      ...base(conversation),
      phase: BookingPhase.ASKING_DATE,
      draft: {
        ...conversation.draft,
        slotId: null,
        startTime: null,
        endTime: null,
        provider: null,
      },
      offeredSlots: [],
      slotChoices: [],
      checkedDate: null,
      checkedAvailability: null,
      action: { type: 'lookup', date: conversation.draft.date },
      reply: 'That slot has just been booked. Please choose another available time.',
    };
  }
  return {
    ...base(conversation),
    phase: BookingPhase.AWAITING_CONFIRMATION,
    reply: 'I could not complete that booking. Reply yes to try again, or no to cancel.',
  };
}

export function cancelBookingDraft() {
  return {
    ...initialBookingConversation(),
    reply: 'I cancelled that booking. Nothing was booked.',
  };
}

export function bookingRequestFromDraft(draft) {
  return {
    slotId: draft.slotId,
    patientName: draft.patientName,
    patientContact: draft.contact,
  };
}

export function isReadyToSubmit(conversation) {
  const { draft } = conversation;
  return conversation.phase === BookingPhase.AWAITING_CONFIRMATION
    && Boolean(draft.slotId)
    && Boolean(draft.patientName)
    && Boolean(draft.contact);
}

export function availableSlotsPath(date) {
  return `/api/appointments/slots?date=${encodeURIComponent(date)}`;
}

export function interpretAppointmentDate(text, today = '2026-10-01') {
  const cleaned = text.trim().replace(/[.!?]+$/g, '').replace(/\s+/g, ' ');
  if (/^\d{1,2}\/\d{1,2}\/\d{2,4}$/.test(cleaned)) return null;

  const iso = cleaned.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (iso) return calendarDate(Number(iso[1]), Number(iso[2]), Number(iso[3]));

  const monthFirst = cleaned.match(/^(?:on\s+)?([A-Za-z]+)\s+(\d{1,2})(?:st|nd|rd|th)?(?:,)?\s+(\d{4})$/i);
  if (monthFirst) {
    const month = MONTHS.get(monthFirst[1].toLowerCase());
    if (!month) return null;
    return calendarDate(Number(monthFirst[3]), month, Number(monthFirst[2]));
  }

  const dayFirst = cleaned.match(/^(?:on\s+)?(\d{1,2})(?:st|nd|rd|th)?\s+([A-Za-z]+)\s+(\d{4})$/i);
  if (dayFirst) {
    const month = MONTHS.get(dayFirst[2].toLowerCase());
    if (!month) return null;
    return calendarDate(Number(dayFirst[3]), month, Number(dayFirst[1]));
  }

  const monthDay = cleaned.match(/^(?:on\s+)?([A-Za-z]+)\s+(\d{1,2})(?:st|nd|rd|th)?$/i);
  if (monthDay) {
    const month = MONTHS.get(monthDay[1].toLowerCase());
    if (!month) return null;
    const day = Number(monthDay[2]);
    const thisYear = Number(today.slice(0, 4));
    const candidate = calendarDate(thisYear, month, day);
    if (!candidate) return null;
    return candidate < today ? calendarDate(thisYear + 1, month, day) : candidate;
  }

  return null;
}

export function clinicToday(now = new Date()) {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'America/Los_Angeles',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(now);
}

function isCancelCommand(phase, message) {
  if (phase === BookingPhase.AWAITING_CANCELLATION_CONFIRMATION
      || phase === BookingPhase.AWAITING_RESCHEDULE_CONFIRMATION) {
    return false;
  }
  const normalized = normalizeReply(message);
  if (normalized === 'cancel' || normalized === 'never mind' || normalized === 'nevermind' || normalized === 'stop') {
    return true;
  }
  if (phase === BookingPhase.COLLECTING_NAME || phase === BookingPhase.COLLECTING_CONTACT) return false;
  return isNegative(message);
}

function consent(conversation, message) {
  if (!isAffirmative(message)) {
    return stay(conversation, 'Reply yes to look up a date, or no to cancel.');
  }
  return {
    ...base(conversation),
    phase: BookingPhase.ASKING_DATE,
    reply: ASK_DATE_PROMPT,
  };
}

function askDate(conversation, message, today) {
  const date = interpretAppointmentDate(message, today);
  if (!date) {
    return stay(conversation, 'I could not read that date. Please give a date like June 10, 2030.');
  }
  if (date < today) {
    return stay(conversation, 'That date has already passed. Which future date would you prefer?');
  }
  if (date === conversation.checkedDate && isUnavailable(conversation.checkedAvailability)) {
    return {
      ...base(conversation),
      phase: BookingPhase.ASKING_DATE,
      reply: repeatedDateReply(date, conversation.checkedAvailability),
    };
  }
  return {
    ...base(conversation),
    phase: BookingPhase.ASKING_DATE,
    action: { type: 'lookup', date },
    reply: null,
  };
}

function unavailableDate(conversation, date, availability, reply) {
  return {
    ...base(conversation),
    phase: BookingPhase.ASKING_DATE,
    offeredSlots: [],
    slotChoices: [],
    checkedDate: date ?? null,
    checkedAvailability: availability,
    reply,
  };
}

function isUnavailable(availability) {
  return availability === 'NO_RECORDS' || availability === 'FULLY_BOOKED'
    || availability === 'CLOSED' || availability === 'OUTSIDE_BOOKING_WINDOW';
}

function repeatedDateReply(date, availability) {
  const shown = formatDisplayDate(date);
  if (availability === 'NO_RECORDS') {
    return `${shown} is the same date I just checked. No appointment records exist for it, so availability still cannot be confirmed. Please provide a different date.`;
  }
  if (availability === 'CLOSED') {
    return `${shown} is the same date I just checked. The clinic is closed then. Please provide a different date.`;
  }
  if (availability === 'OUTSIDE_BOOKING_WINDOW') {
    return `${shown} is the same date I just checked, and it is still outside the booking window. Please provide a different date.`;
  }
  return `${shown} is the same date I just checked. No appointments are currently available on it. Please provide a different date.`;
}

function chooseSlot(conversation, message) {
  const index = slotIndex(conversation, message);
  if (index == null) {
    return stay(conversation, 'Please choose one of the listed slots by its number.');
  }
  const slot = conversation.offeredSlots[index];
  const draft = {
    ...conversation.draft,
    slotId: String(slot.id),
    date: slot.date,
    startTime: slot.startTime,
    endTime: slot.endTime,
    provider: slot.provider ?? null,
  };
  if (conversation.purpose === 'reschedule') {
    return {
      ...base(conversation),
      phase: BookingPhase.AWAITING_RESCHEDULE_CONFIRMATION,
      draft,
      offeredSlots: conversation.offeredSlots,
      reply: rescheduleSummary(conversation.existingBooking, draft),
    };
  }
  if (draft.patientName && draft.contact) {
    return {
      ...base(conversation),
      phase: BookingPhase.AWAITING_CONFIRMATION,
      draft,
      offeredSlots: conversation.offeredSlots,
      reply: confirmationSummary(draft),
    };
  }
  return {
    ...base(conversation),
    phase: BookingPhase.COLLECTING_NAME,
    draft,
    offeredSlots: conversation.offeredSlots,
    reply: 'Great. May I have your full name?',
  };
}

function collectName(conversation, message) {
  const name = message.trim();
  if (!name || name.length < 2 || name.length > 120 || isNegative(name)) {
    return stay(conversation, 'Please enter the patient\'s full name.');
  }
  return {
    ...base(conversation),
    phase: BookingPhase.COLLECTING_CONTACT,
    draft: { ...conversation.draft, patientName: name },
    reply: 'What phone number or contact method should the clinic use?',
  };
}

function collectContact(conversation, message) {
  const contact = normalizeIndianMobile(message);
  if (!contact) {
    return stay(conversation, 'Please enter a valid 10-digit Indian mobile number, such as 98765 43210.');
  }
  const draft = { ...conversation.draft, contact };
  return {
    ...base(conversation),
    phase: BookingPhase.AWAITING_CONFIRMATION,
    draft,
    reply: confirmationSummary(draft),
  };
}

function confirm(conversation, message) {
  if (isNegative(message)) return cancelBookingDraft();
  if (!isAffirmative(message) || !isReadyToSubmit(conversation)) {
    return stay(conversation, 'Reply yes to book, or no to cancel.');
  }
  return {
    ...base(conversation),
    phase: BookingPhase.BOOKING,
    action: { type: 'book', body: bookingRequestFromDraft(conversation.draft) },
    reply: null,
  };
}

function stay(conversation, reply) {
  return { ...base(conversation), reply };
}

function base(conversation) {
  return {
    phase: conversation.phase,
    draft: conversation.draft,
    offeredSlots: conversation.offeredSlots ?? [],
    listingId: conversation.listingId ?? 0,
    reply: null,
    action: null,
    slotChoices: [],
    checkedDate: conversation.checkedDate ?? null,
    checkedAvailability: conversation.checkedAvailability ?? null,
    purpose: conversation.purpose ?? null,
    existingBooking: conversation.existingBooking ?? null,
    cancellationRequestId: conversation.cancellationRequestId ?? null,
  };
}

const BOOKING_REFERENCE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function isNewBookingRequest(text) {
  const normalized = normalizeReply(text);
  return normalized === 'book an appointment'
    || /^(i )?(want to |would like to |please )?(book|schedule)( an| a)?( dental)? appointment$/.test(normalized);
}

function isCancellationRequest(text) {
  return /^(i )?(want to |would like to |please )?(cancel)( my)? appointment$/.test(normalizeReply(text));
}

function isRescheduleRequest(text) {
  return /^(i )?(want to |would like to |please )?(reschedule|move)( my)? appointment$/.test(normalizeReply(text));
}

function acceptReference(conversation, message) {
  const reference = message.trim();
  if (!BOOKING_REFERENCE.test(reference)) {
    return stay(conversation, 'Please enter the booking reference from your confirmation. It looks like 6d9802e8-6fce-4e1c-9d47-5c6b1f8d1001.');
  }
  return {
    ...base(conversation),
    action: { type: 'inspect', slotId: reference, purpose: conversation.purpose },
    reply: null,
  };
}

export function applyInspectedBooking(conversation, slot) {
  if (!slot?.id || slot.status !== 'BOOKED') {
    return stay(conversation, 'That reference is not an active booking. Please check it and try again.');
  }
  const existingBooking = {
    slotId: String(slot.id),
    date: slot.date,
    startTime: slot.startTime,
    endTime: slot.endTime,
    provider: slot.provider ?? null,
    status: slot.status,
  };
  if (conversation.purpose === 'reschedule') {
    return {
      ...base(conversation),
      phase: BookingPhase.AWAITING_RESCHEDULE_DATE,
      existingBooking,
      reply: `I found ${formatDisplayDate(slot.date)} at ${formatTime(slot.startTime)} with ${slot.provider || 'the clinic'}. Which new date would you prefer?`,
    };
  }
  return cancellationConfirmation(conversation, {
    id: slot.id, date: slot.date, startTime: slot.startTime, endTime: slot.endTime, provider: slot.provider,
  });
}

export function applyCancellationMatches(conversation, slots) {
  const matches = Array.isArray(slots) ? slots.filter(usableSlot) : [];
  if (matches.length === 0) {
    return { ...base(conversation), phase: BookingPhase.AWAITING_CANCELLATION_NAME,
      draft: { ...conversation.draft, patientName: '', contact: '' },
      reply: 'I could not find an upcoming appointment with those details. Please try the registered name again.' };
  }
  const offeredSlots = matches.map(toOfferedSlot);
  if (matches.length > 1) {
    return { ...base(conversation), phase: BookingPhase.SELECTING_CANCELLATION, offeredSlots,
      listingId: (conversation.listingId ?? 0) + 1,
      slotChoices: offeredSlots.map((slot, index) => ({ index, label: `${formatDisplayDate(slot.date)} · ${slot.label}` })),
      reply: 'I found multiple upcoming appointments. Please choose the one you want to cancel.' };
  }
  return cancellationConfirmation(conversation, offeredSlots[0]);
}

export function applyCancellationOtpIssued(conversation, issued) {
  const reply = issued?.developmentOtp
    ? `A verification code was sent to your registered phone. Enter the 6-digit code. (Local development code: ${issued.developmentOtp})`
    : 'A verification code was sent to your registered phone. Enter the 6-digit code.';
  return { ...base(conversation), phase: BookingPhase.AWAITING_CANCELLATION_OTP,
    cancellationRequestId: issued?.requestId ?? null,
    existingBooking: conversation.existingBooking,
    reply };
}

export function applyCancellationOtpVerified(conversation) {
  return { ...base(conversation), phase: BookingPhase.AWAITING_CANCELLATION_FINAL_CONFIRMATION,
    cancellationRequestId: conversation.cancellationRequestId,
    existingBooking: conversation.existingBooking,
    reply: `${appointmentSummary(conversation.existingBooking)}\n\nYour phone number is verified. Reply yes to cancel this appointment, or no to keep it.` };
}

function collectCancellationName(conversation, message) {
  const name = message.trim();
  if (!name || name.length < 2 || name.length > 120 || isNegative(name)) return stay(conversation, 'Please enter the patient\'s registered full name.');
  return { ...base(conversation), phase: BookingPhase.AWAITING_CANCELLATION_CONTACT,
    draft: { ...conversation.draft, patientName: name }, reply: 'What phone number is registered for the appointment?' };
}

function collectCancellationContact(conversation, message) {
  const contact = normalizeIndianMobile(message);
  if (!contact) return stay(conversation, 'Please enter the registered 10-digit Indian mobile number.');
  return { ...base(conversation), phase: BookingPhase.AWAITING_CANCELLATION_CONTACT,
    draft: { ...conversation.draft, contact }, action: { type: 'findCancellation', body: { patientName: conversation.draft.patientName, patientContact: contact } } };
}

function normalizeIndianMobile(value) {
  if (typeof value !== 'string') return null;
  const compact = value.trim().replace(/[\s().-]/g, '');
  let national;
  if (compact.startsWith('+91')) {
    national = compact.slice(3);
  } else if (compact.startsWith('91') && compact.length === 12) {
    national = compact.slice(2);
  } else {
    national = compact;
  }
  return /^[6-9]\d{9}$/.test(national) ? national : null;
}

function chooseCancellation(conversation, message) {
  const index = slotIndex(conversation, message);
  if (index == null) return stay(conversation, 'Please choose one of the listed appointments by its number.');
  return cancellationConfirmation(conversation, conversation.offeredSlots[index]);
}

function cancellationConfirmation(conversation, slot) {
  const existingBooking = { slotId: String(slot.id), date: slot.date, startTime: slot.startTime, endTime: slot.endTime, provider: slot.provider ?? null, status: 'BOOKED' };
  return { ...base(conversation), phase: BookingPhase.AWAITING_CANCELLATION_OTP_REQUEST, existingBooking,
    reply: `${appointmentSummary(existingBooking)}\n\nReply yes to receive a verification code before cancelling, or no to keep it.` };
}

function requestCancellationOtp(conversation, message) {
  if (isNegative(message)) return { ...initialBookingConversation(), reply: 'I left that appointment unchanged.' };
  if (!isAffirmative(message) || !conversation.existingBooking?.slotId) {
    return stay(conversation, 'Reply yes to receive a verification code, or no to keep the appointment.');
  }
  return { ...base(conversation), phase: BookingPhase.AWAITING_CANCELLATION_OTP_REQUEST,
    existingBooking: conversation.existingBooking,
    action: { type: 'requestCancellationOtp', slotId: conversation.existingBooking.slotId,
      patientName: conversation.draft.patientName, patientContact: conversation.draft.contact } };
}

function enterCancellationOtp(conversation, message) {
  if (isResendCommand(message)) return { ...base(conversation), action: { type: 'resendCancellationOtp', requestId: conversation.cancellationRequestId }, reply: null };
  if (!/^\d{6}$/.test(message.trim())) return stay(conversation, 'Please enter the 6-digit verification code, or reply resend to request a new code.');
  return { ...base(conversation), phase: BookingPhase.AWAITING_CANCELLATION_OTP,
    existingBooking: conversation.existingBooking, cancellationRequestId: conversation.cancellationRequestId,
    action: { type: 'verifyCancellationOtp', requestId: conversation.cancellationRequestId, otp: message.trim() } };
}

function confirmVerifiedCancellation(conversation, message) {
  if (isNegative(message)) return { ...initialBookingConversation(), reply: 'I left that appointment unchanged.' };
  if (!isAffirmative(message) || !conversation.cancellationRequestId) return stay(conversation, 'Reply yes to cancel the verified appointment, or no to keep it.');
  return { ...base(conversation), phase: BookingPhase.CANCELLING, existingBooking: conversation.existingBooking,
    cancellationRequestId: conversation.cancellationRequestId, action: { type: 'cancelVerified', requestId: conversation.cancellationRequestId } };
}

function appointmentSummary(booking) {
  return `I found this appointment:\nDate: ${formatDisplayDate(booking.date)}\nTime: ${formatTime(booking.startTime)} – ${formatTime(booking.endTime)}${booking.provider ? `\nProvider: ${booking.provider}` : ''}`;
}

function isResendCommand(message) {
  return /^(resend|resend code|send another code)$/.test(normalizeReply(message));
}

function confirmCancellation(conversation, message) {
  if (isNegative(message)) {
    return { ...initialBookingConversation(), reply: 'I left that appointment unchanged.' };
  }
  return stay(conversation, 'Cancellation now requires phone verification. Please start again from the cancellation menu.');
}

function confirmReschedule(conversation, message) {
  if (isNegative(message)) {
    return { ...initialBookingConversation(), reply: 'I left the original appointment unchanged.' };
  }
  if (!isAffirmative(message) || !conversation.existingBooking?.slotId || !conversation.draft?.slotId) {
    return stay(conversation, 'Reply yes to move the appointment, or no to keep the original time.');
  }
  return {
    ...base(conversation),
    phase: BookingPhase.BOOKING,
    action: {
      type: 'reschedule',
      fromSlotId: conversation.existingBooking.slotId,
      toSlotId: conversation.draft.slotId,
    },
    reply: null,
  };
}

function rescheduleSummary(existing, draft) {
  const from = existing
    ? `${formatDisplayDate(existing.date)} at ${formatTime(existing.startTime)}`
    : 'the current appointment';
  return `Please confirm this change:\nFrom: ${from}\nTo: ${formatDisplayDate(draft.date)} at ${formatTime(draft.startTime)}${draft.provider ? ` with ${draft.provider}` : ''}\n\nReply yes to reschedule, or no to keep the original appointment.`;
}

function usableSlot(slot) {
  return Boolean(slot?.id && slot.date && slot.startTime && slot.endTime);
}

function toOfferedSlot(slot) {
  const provider = slot.provider ? ` with ${slot.provider}` : '';
  return {
    id: String(slot.id),
    date: slot.date,
    startTime: slot.startTime,
    endTime: slot.endTime,
    provider: slot.provider ?? null,
    label: `${formatTime(slot.startTime)} – ${formatTime(slot.endTime)}${provider}`,
  };
}

function slotIndex(conversation, message) {
  const slots = conversation.offeredSlots ?? [];
  const normalized = normalizeReply(message);
  const numbered = normalized.match(/^(?:slot\s+)?(\d+)$/);
  if (numbered) {
    const index = Number(numbered[1]) - 1;
    return index >= 0 && index < slots.length ? index : null;
  }
  const word = ORDINAL_WORDS.get(normalized) ?? ORDINAL_WORDS.get(normalized.replace(/^the\s+/, ''));
  if (word) {
    return word - 1 < slots.length ? word - 1 : null;
  }
  const wanted = clockMinutes(normalized);
  if (wanted == null) return null;
  const matches = slots.filter((slot) => clockMinutes(formatTime(slot.startTime).toLowerCase()) === wanted
    || clockMinutes(slot.startTime) === wanted);
  return matches.length === 1 ? slots.indexOf(matches[0]) : null;
}

function confirmationSummary(draft) {
  const provider = draft.provider ? `\nProvider: ${draft.provider}` : '';
  return `Please confirm this appointment:\nDate: ${formatDisplayDate(draft.date)}\nTime: ${formatTime(draft.startTime)} – ${formatTime(draft.endTime)}${provider}\nName: ${draft.patientName}\nContact: ${draft.contact}\n\nWould you like me to confirm this appointment? Reply yes to book, or no to cancel.`;
}

function normalizeReply(text) {
  return text
    .trim()
    .toLowerCase()
    .replace(/[’]/g, "'")
    .replace(/[.!?]+$/g, '')
    .replace(/,/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

function calendarDate(year, month, day) {
  if (!Number.isInteger(year) || !Number.isInteger(month) || !Number.isInteger(day)) return null;
  if (month < 1 || month > 12 || day < 1 || day > 31) return null;
  const utc = new Date(Date.UTC(year, month - 1, day));
  if (utc.getUTCFullYear() !== year || utc.getUTCMonth() !== month - 1 || utc.getUTCDate() !== day) return null;
  return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

export function formatDisplayDate(iso) {
  if (!iso) return 'that date';
  const [year, month, day] = iso.split('-').map(Number);
  return new Intl.DateTimeFormat('en-US', {
    month: 'long',
    day: 'numeric',
    year: 'numeric',
    timeZone: 'UTC',
  }).format(new Date(Date.UTC(year, month - 1, day)));
}

export function formatTime(value) {
  const [hourText, minuteText] = String(value).split(':');
  const hour = Number(hourText);
  const minute = Number(minuteText);
  if (!Number.isInteger(hour) || !Number.isInteger(minute)) return String(value);
  const suffix = hour >= 12 ? 'PM' : 'AM';
  const displayHour = hour % 12 || 12;
  return `${displayHour}:${String(minute).padStart(2, '0')} ${suffix}`;
}

function clockMinutes(value) {
  const text = String(value).trim().toLowerCase();
  const twelve = text.match(/^(\d{1,2})(?::(\d{2}))?\s*(am|pm)$/);
  if (twelve) {
    let hour = Number(twelve[1]);
    const minute = Number(twelve[2] ?? '0');
    const suffix = twelve[3];
    if (hour < 1 || hour > 12 || minute < 0 || minute > 59) return null;
    if (suffix === 'pm' && hour !== 12) hour += 12;
    if (suffix === 'am' && hour === 12) hour = 0;
    return hour * 60 + minute;
  }
  const twentyFour = text.match(/^(\d{1,2}):(\d{2})(?::\d{2})?$/);
  if (!twentyFour) return null;
  const hour = Number(twentyFour[1]);
  const minute = Number(twentyFour[2]);
  if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
  return hour * 60 + minute;
}
