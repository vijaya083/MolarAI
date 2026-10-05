import assert from 'node:assert/strict';
import test from 'node:test';
import {
  ASK_DATE_PROMPT,
  BookingPhase,
  applyAvailableSlots,
  applyCancellationMatches,
  applyCancellationOtpIssued,
  applyCancellationOtpVerified,
  applyInspectedBooking,
  availableSlotsPath,
  bookingFailure,
  bookingRequestFromDraft,
  bookingSucceeded,
  cancelBookingDraft,
  emptyBookingDraft,
  handleBookingMessage,
  initialBookingConversation,
  isBookingInProgress,
  isReadyToSubmit,
  offerBooking,
  routeIdleMessage,
  shouldAskKnowledge,
} from './bookingConversation.js';
import {
  AppointmentApiError,
  KnowledgeUnavailableError,
  askMolarAI,
  bookAppointment,
  fetchAvailableSlots,
} from './knowledgeApi.js';
import { isCancellationEnabled } from './demoSettings.js';

const today = '2026-10-01';
const slot = {
  id: '4f1c0a2e-7b3d-4c8a-9e11-2a6b8d0c5f17',
  date: '2030-06-10',
  startTime: '09:00:00',
  endTime: '09:30:00',
  status: 'AVAILABLE',
  provider: 'Maya Chen',
};
const laterSlot = {
  id: '8c2a6d44-1e77-4b0a-93c1-6f0e2b7a91d4',
  date: '2030-06-10',
  startTime: '10:00:00',
  endTime: '10:30:00',
  status: 'AVAILABLE',
  provider: 'Jordan Lee',
};

function advance(conversation, text) {
  return handleBookingMessage(conversation, text, today);
}

function readyToConfirm() {
  const offered = offerBooking();
  const asking = advance(offered, 'sure');
  const lookup = advance(asking, 'June 10, 2030');
  const listed = applyAvailableSlots(lookup, [slot, laterSlot]);
  const named = advance(advance(listed, '1'), 'Ada Lovelace');
  return advance(named, '98765 43210');
}

test('booking offer waits for consent and does not ask the FAQ path', () => {
  const idle = initialBookingConversation();
  assert.equal(idle.phase, BookingPhase.IDLE);
  assert.deepEqual(idle.draft, emptyBookingDraft());
  assert.equal(shouldAskKnowledge(idle.phase), true);
  assert.equal(isBookingInProgress(idle.phase), false);

  const offered = offerBooking();
  assert.equal(offered.phase, BookingPhase.AWAITING_BOOKING_CONSENT);
  assert.equal(shouldAskKnowledge(offered.phase), false);
  assert.equal(offered.action, null);
  assert.equal(isReadyToSubmit(offered), false);
});

test('sure after booking consent asks for a date and does not book', () => {
  for (const phrase of ['sure', 'yes', 'okay', 'ok', "let's do it", 'go ahead', 'please']) {
    const next = advance(offerBooking(), phrase);
    assert.equal(next.phase, BookingPhase.ASKING_DATE, phrase);
    assert.equal(next.reply, ASK_DATE_PROMPT, phrase);
    assert.equal(next.action, null, phrase);
    assert.equal(shouldAskKnowledge(next.phase), false, phrase);
  }
});

test('a negative reply cancels consent without a booking action', () => {
  for (const phrase of ['no', 'nope', 'no thanks', 'cancel']) {
    const next = advance(offerBooking(), phrase);
    assert.equal(next.phase, BookingPhase.IDLE, phrase);
    assert.equal(next.action, null, phrase);
    assert.equal(next.draft.contact, '', phrase);
    assert.match(next.reply, /Nothing was booked/);
  }
});

test('an unclear consent reply stays pending and is not treated as FAQ', () => {
  const next = advance(offerBooking(), 'what are your clinic hours?');
  assert.equal(next.phase, BookingPhase.AWAITING_BOOKING_CONSENT);
  assert.equal(next.action, null);
  assert.equal(shouldAskKnowledge(next.phase), false);
});

test('date collection requests slots and an empty result invents no times', () => {
  const asking = advance(offerBooking(), 'yes');
  const lookup = advance(asking, 'June 10, 2030');
  assert.equal(lookup.phase, BookingPhase.ASKING_DATE);
  assert.deepEqual(lookup.action, { type: 'lookup', date: '2030-06-10' });
  assert.equal(availableSlotsPath(lookup.action.date), '/api/appointments/slots?date=2030-06-10');

  const listed = applyAvailableSlots(lookup, [slot, laterSlot]);
  assert.equal(listed.phase, BookingPhase.SELECTING_SLOT);
  assert.equal(listed.reply, 'Here are the available appointments for June 10, 2030.');
  assert.equal(listed.slotChoices.length, 2);
  assert.match(listed.slotChoices[0].label, /9:00 AM – 9:30 AM with Maya Chen/);
  assert.match(listed.slotChoices[1].label, /10:00 AM – 10:30 AM with Jordan Lee/);
  assert.equal(listed.reply.includes('9:00 AM'), false);
  assert.equal(listed.reply.includes(slot.id), false);
  assert.equal(listed.action, null);

  const none = applyAvailableSlots(lookup, { availability: 'NO_RECORDS', slots: [] });
  assert.equal(none.phase, BookingPhase.ASKING_DATE);
  assert.match(none.reply, /No appointment records exist for June 10, 2030, so availability cannot be confirmed/);
  assert.equal(none.reply.includes('9:00'), false);
  assert.equal(none.action, null);
});

test('an availability response offers six slot cards without a numbered list', () => {
  const lookup = advance(advance(offerBooking(), 'yes'), 'October 10, 2026');
  const starts = [
    ['09:00:00', '09:30:00'],
    ['09:30:00', '10:00:00'],
    ['10:00:00', '10:30:00'],
    ['11:00:00', '11:30:00'],
    ['12:00:00', '12:30:00'],
    ['13:30:00', '14:00:00'],
  ];
  const slots = starts.map(([startTime, endTime], index) => ({
    id: `00000000-0000-4000-8000-00000000000${index + 1}`,
    date: '2026-10-10',
    startTime,
    endTime,
    status: 'AVAILABLE',
    provider: index % 2 === 0 ? 'Dr. Maya Chen' : 'Dr. Jordan Lee',
  }));

  const listed = applyAvailableSlots(lookup, { availability: 'AVAILABLE', slots });

  assert.equal(listed.reply, 'Here are the available appointments for October 10, 2026.');
  assert.equal(listed.slotChoices.length, 6);
  assert.equal(listed.offeredSlots.length, 6);
  assert.equal(/\n\d+\.\s/.test(listed.reply), false);
  assert.equal(listed.reply.includes('Reply with the number'), false);
  assert.deepEqual(listed.slotChoices.map((choice) => choice.label), [
    '9:00 AM – 9:30 AM with Dr. Maya Chen',
    '9:30 AM – 10:00 AM with Dr. Jordan Lee',
    '10:00 AM – 10:30 AM with Dr. Maya Chen',
    '11:00 AM – 11:30 AM with Dr. Jordan Lee',
    '12:00 PM – 12:30 PM with Dr. Maya Chen',
    '1:30 PM – 2:00 PM with Dr. Jordan Lee',
  ]);
  const selected = advance(listed, '4');
  assert.equal(selected.draft.slotId, slots[3].id);
  assert.equal(selected.draft.provider, 'Dr. Jordan Lee');
});

test('fully booked dates are distinct and booked slots cannot be selected', () => {
  const lookup = advance(advance(offerBooking(), 'yes'), 'June 10, 2030');
  const booked = { ...laterSlot, status: 'BOOKED' };
  const open = { ...slot, status: 'AVAILABLE' };

  const full = applyAvailableSlots(lookup, { availability: 'FULLY_BOOKED', slots: [booked] });
  assert.equal(full.phase, BookingPhase.ASKING_DATE);
  assert.match(full.reply, /No appointments are currently available on June 10, 2030/);
  assert.equal(full.offeredSlots.length, 0);
  assert.equal(full.reply.includes(booked.id), false);

  const mixed = applyAvailableSlots(lookup, { availability: 'AVAILABLE', slots: [booked, open] });
  assert.equal(mixed.phase, BookingPhase.SELECTING_SLOT);
  assert.deepEqual(mixed.offeredSlots.map((item) => item.id), [open.id]);
  assert.equal(mixed.reply.includes('10:00 AM'), false);
  const selected = advance(mixed, '1');
  assert.equal(selected.draft.slotId, open.id);
  assert.notEqual(selected.draft.slotId, booked.id);
});

test('repeating an unavailable date asks for a different one without another lookup', () => {
  const lookup = advance(advance(offerBooking(), 'yes'), 'October 6, 2026');
  assert.deepEqual(lookup.action, { type: 'lookup', date: '2026-10-06' });
  assert.equal(shouldAskKnowledge(lookup.phase), false);

  const missing = applyAvailableSlots(lookup, { availability: 'NO_RECORDS', slots: [] });
  assert.equal(missing.phase, BookingPhase.ASKING_DATE);
  const repeatedMissing = advance(missing, 'October 6');
  assert.equal(repeatedMissing.phase, BookingPhase.ASKING_DATE);
  assert.equal(repeatedMissing.action, null);
  assert.notEqual(repeatedMissing.reply, missing.reply);
  assert.match(repeatedMissing.reply, /same date/);
  assert.match(repeatedMissing.reply, /cannot be confirmed/);
  assert.match(repeatedMissing.reply, /different date/);

  const bookedOut = applyAvailableSlots(lookup, { availability: 'FULLY_BOOKED', slots: [] });
  const repeatedBooked = advance(bookedOut, 'October 6, 2026');
  assert.equal(repeatedBooked.phase, BookingPhase.ASKING_DATE);
  assert.equal(repeatedBooked.action, null);
  assert.notEqual(repeatedBooked.reply, bookedOut.reply);
  assert.notEqual(repeatedBooked.reply, repeatedMissing.reply);
  assert.match(repeatedBooked.reply, /same date/);
  assert.match(repeatedBooked.reply, /currently available/);
  assert.match(missing.reply, /cannot be confirmed/);
  assert.match(bookedOut.reply, /currently available/);
});

test('a different date after no availability is looked up again and can continue', () => {
  const lookup = advance(advance(offerBooking(), 'yes'), 'October 6, 2026');
  const missing = applyAvailableSlots(lookup, { availability: 'NO_RECORDS', slots: [] });
  assert.equal(missing.phase, BookingPhase.ASKING_DATE);

  const unclear = advance(missing, 'sometime soon');
  assert.equal(unclear.phase, BookingPhase.ASKING_DATE);
  assert.equal(unclear.action, null);
  const past = advance(missing, 'May 1, 2020');
  assert.equal(past.phase, BookingPhase.ASKING_DATE);
  assert.match(past.reply, /already passed/);
  assert.equal(past.action, null);

  const nextLookup = advance(missing, 'June 10, 2030');
  assert.equal(nextLookup.phase, BookingPhase.ASKING_DATE);
  assert.deepEqual(nextLookup.action, { type: 'lookup', date: '2030-06-10' });

  const listed = applyAvailableSlots(nextLookup, { availability: 'AVAILABLE', slots: [{ ...slot, status: 'AVAILABLE' }] });
  assert.equal(listed.phase, BookingPhase.SELECTING_SLOT);
  assert.equal(listed.checkedDate, null);
  assert.equal(listed.reply, 'Here are the available appointments for June 10, 2030.');
  assert.match(listed.slotChoices[0].label, /9:00 AM/);
  const named = advance(listed, '1');
  assert.equal(named.phase, BookingPhase.COLLECTING_NAME);
  assert.equal(named.draft.slotId, slot.id);
  assert.equal(named.action, null);
});

test('an unreadable or past date stays on the date question', () => {
  const asking = advance(offerBooking(), 'yes');
  const slash = advance(asking, '6/10/2030');
  assert.equal(slash.phase, BookingPhase.ASKING_DATE);
  assert.equal(slash.action, null);

  const past = advance(asking, 'May 1, 2020');
  assert.equal(past.phase, BookingPhase.ASKING_DATE);
  assert.equal(past.action, null);
  assert.match(past.reply, /already passed/);
});

test('slot selection stores the slot id and does not ask for a UUID', () => {
  const asking = advance(offerBooking(), 'yes');
  const listed = applyAvailableSlots(advance(asking, '2030-06-10'), [slot, laterSlot]);
  const selected = advance(listed, '2');
  assert.equal(selected.phase, BookingPhase.COLLECTING_NAME);
  assert.equal(selected.draft.slotId, laterSlot.id);
  assert.equal(selected.draft.startTime, '10:00:00');
  assert.equal(selected.reply.includes('UUID'), false);
  assert.equal(selected.reply.includes(laterSlot.id), false);
  assert.equal(selected.action, null);

  const byTime = advance(listed, '9 AM');
  assert.equal(byTime.draft.slotId, slot.id);

  const invalid = advance(listed, '9');
  assert.equal(invalid.phase, BookingPhase.SELECTING_SLOT);
  assert.equal(invalid.draft.slotId, null);
  assert.equal(invalid.action, null);
});

test('name and contact stay in the draft until confirmation', () => {
  const listed = applyAvailableSlots(advance(advance(offerBooking(), 'yes'), 'June 10, 2030'), [slot]);
  const selected = advance(listed, '1');
  const blankName = advance(selected, '   ');
  assert.equal(blankName.phase, BookingPhase.COLLECTING_NAME);

  const named = advance(selected, '  Ada Lovelace  ');
  assert.equal(named.phase, BookingPhase.COLLECTING_CONTACT);
  assert.equal(named.draft.patientName, 'Ada Lovelace');
  assert.equal(named.draft.contact, '');
  assert.equal(isReadyToSubmit(named), false);

  const blankContact = advance(named, ' ');
  assert.equal(blankContact.phase, BookingPhase.COLLECTING_CONTACT);
  assert.equal(blankContact.draft.contact, '');

  const invalid = advance(named, 'ada@example.com');
  assert.equal(invalid.phase, BookingPhase.COLLECTING_CONTACT);
  assert.equal(invalid.draft.contact, '');
  assert.match(invalid.reply, /valid 10-digit Indian mobile number/);

  const confirming = advance(named, '+91 98765 43210');
  assert.equal(confirming.phase, BookingPhase.AWAITING_CONFIRMATION);
  assert.equal(confirming.action, null);
  assert.match(confirming.reply, /Ada Lovelace/);
  assert.match(confirming.reply, /9876543210/);
  assert.match(confirming.reply, /9:00 AM – 9:30 AM/);
  assert.equal(confirming.reply.includes(slot.id), false);
  assert.deepEqual(bookingRequestFromDraft(confirming.draft), {
    slotId: slot.id,
    patientName: 'Ada Lovelace',
    patientContact: '9876543210',
  });
});

test('phone validation rejects text and empty input, then normalizes Indian formats on retry', () => {
  const listed = applyAvailableSlots(advance(advance(offerBooking(), 'yes'), 'June 10, 2030'), [slot]);
  const named = advance(advance(listed, '1'), 'Ada Lovelace');

  for (const invalid of ['', 'phone number', 'abcdefghij', '12345', '5123456789', '+91 98765 4321']) {
    const rejected = advance(named, invalid);
    assert.equal(rejected.phase, BookingPhase.COLLECTING_CONTACT, invalid);
    assert.equal(rejected.draft.contact, '', invalid);
    assert.equal(rejected.action, null, invalid);
  }

  const plusCountryCode = advance(named, '+91 (98765) 43210');
  assert.equal(plusCountryCode.draft.contact, '9876543210');
  assert.equal(plusCountryCode.phase, BookingPhase.AWAITING_CONFIRMATION);

  const nationalWithCountryCode = advance(named, '91-98765-43210');
  assert.equal(nationalWithCountryCode.draft.contact, '9876543210');
  assert.match(nationalWithCountryCode.reply, /Contact: 9876543210/);
});

test('confirmation is required before a booking request exists', () => {
  const confirming = readyToConfirm();
  assert.equal(confirming.action, null);
  assert.equal(isReadyToSubmit(confirming), true);

  const unclear = advance(confirming, 'maybe later');
  assert.equal(unclear.phase, BookingPhase.AWAITING_CONFIRMATION);
  assert.equal(unclear.action, null);

  const booking = advance(confirming, 'yes');
  assert.equal(booking.phase, BookingPhase.BOOKING);
  assert.equal(booking.action.type, 'book');
  assert.deepEqual(booking.action.body, {
    slotId: slot.id,
    patientName: 'Ada Lovelace',
    patientContact: '9876543210',
  });
});

test('a negative confirmation cancels the pending draft and does not book', () => {
  const confirming = readyToConfirm();
  const cancelled = advance(confirming, 'no');
  assert.equal(cancelled.phase, BookingPhase.IDLE);
  assert.equal(cancelled.action, null);
  assert.equal(cancelled.draft.slotId, null);
  assert.equal(cancelled.draft.patientName, '');
  assert.equal(cancelled.draft.contact, '');
  assert.match(cancelled.reply, /Nothing was booked/);
});

test('a successful booking shows the reference and a conflict refreshes that date', () => {
  const booking = advance(readyToConfirm(), 'yes');
  const done = bookingSucceeded(booking, { id: 'booked-ref-1' });
  assert.equal(done.phase, BookingPhase.BOOKED);
  assert.match(done.reply, /Reference: booked-ref-1/);
  assert.equal(done.draft.contact, '');
  assert.equal(shouldAskKnowledge(done.phase), true);

  const taken = bookingFailure(booking, 409);
  assert.match(taken.reply, /That slot has just been booked/);
  assert.deepEqual(taken.action, { type: 'lookup', date: '2030-06-10' });
  assert.equal(taken.draft.slotId, null);
  const refreshed = applyAvailableSlots(taken, { availability: 'AVAILABLE', slots: [{ ...laterSlot, status: 'AVAILABLE' }] });
  assert.equal(refreshed.phase, BookingPhase.SELECTING_SLOT);
  assert.deepEqual(refreshed.offeredSlots.map((item) => item.id), [laterSlot.id]);
});

test('go to menu resets an in-progress booking without a booking action', () => {
  const confirming = readyToConfirm();
  const reset = initialBookingConversation();
  assert.equal(reset.phase, BookingPhase.IDLE);
  assert.equal(reset.action, null);
  assert.deepEqual(reset.draft, emptyBookingDraft());
  assert.notEqual(confirming.draft.contact, reset.draft.contact);
  const cancelled = cancelBookingDraft();
  assert.equal(cancelled.action, null);
  assert.equal(cancelled.draft.contact, '');
});

test('fetchAvailableSlots keeps each availability state and drops booked rows', async () => {
  const original = globalThis.fetch;
  const cases = [
    { availability: 'NO_RECORDS', slots: [] },
    { availability: 'FULLY_BOOKED', slots: [{ ...laterSlot, status: 'BOOKED' }] },
    { availability: 'AVAILABLE', slots: [{ ...slot, status: 'AVAILABLE' }, { ...laterSlot, status: 'BOOKED' }] },
  ];
  try {
    for (const payload of cases) {
      globalThis.fetch = async (url) => {
        assert.equal(url, '/api/appointments/slots?date=2030-06-10');
        return { ok: true, status: 200, json: async () => payload };
      };
      const result = await fetchAvailableSlots('2030-06-10');
      assert.equal(result.availability, payload.availability);
      assert.equal(result.slots.every((item) => item.status === 'AVAILABLE'), true);
      if (payload.availability === 'AVAILABLE') {
        assert.deepEqual(result.slots.map((item) => item.id), [slot.id]);
        assert.equal(result.slots[0].provider, 'Maya Chen');
      } else {
        assert.deepEqual(result.slots, []);
      }
    }
  } finally {
    globalThis.fetch = original;
  }
});

test('bookAppointment reports HTTP 409 without copying the response body', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/appointments/book');
    assert.equal(options.method, 'POST');
    const body = JSON.parse(options.body);
    assert.equal(body.slotId, slot.id);
    assert.equal(body.patientName, 'Ada Lovelace');
    assert.equal(body.patientContact, '555-0100');
    return {
      ok: false,
      status: 409,
      json: async () => ({ message: 'patient secret 555-0100' }),
    };
  };
  try {
    await assert.rejects(
      () => bookAppointment({ slotId: slot.id, patientName: 'Ada Lovelace', patientContact: '555-0100' }),
      (error) => {
        assert.equal(error instanceof AppointmentApiError, true);
        assert.equal(error.status, 409);
        assert.equal(error.message.includes('555-0100'), false);
        assert.equal(error.message.includes('secret'), false);
        return true;
      },
    );
  } finally {
    globalThis.fetch = original;
  }
});

test('bookAppointment returns the booking reference on success', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async () => ({
    ok: true,
    status: 201,
    json: async () => ({
      id: 'booked-ref-1',
      date: '2030-06-10',
      startTime: '09:00:00',
      endTime: '09:30:00',
      status: 'BOOKED',
      provider: 'Maya Chen',
    }),
  });
  try {
    const booked = await bookAppointment({ slotId: slot.id, patientName: 'Ada Lovelace', patientContact: '555-0100' });
    assert.equal(booked.id, 'booked-ref-1');
    assert.equal(booked.status, 'BOOKED');
    assert.equal('patientContact' in booked, false);
  } finally {
    globalThis.fetch = original;
  }
});

test('knowledge-unavailable API response has a clear frontend message', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async () => ({
    ok: false,
    status: 503,
    json: async () => ({
      code: 'KNOWLEDGE_UNAVAILABLE',
      error: 'Knowledge-based answers are temporarily unavailable.',
    }),
  });
  try {
    await assert.rejects(
      () => askMolarAI('What services do you offer?'),
      (error) => {
        assert.equal(error instanceof KnowledgeUnavailableError, true);
        assert.equal(error.message, 'Knowledge-based answers are temporarily unavailable.');
        return true;
      },
    );
  } finally {
    globalThis.fetch = original;
  }
});

test('an explicit booking request starts date selection and does not use the FAQ path', () => {
  const started = routeIdleMessage('I want to book a dental appointment.');
  assert.equal(started.phase, BookingPhase.ASKING_DATE);
  assert.equal(started.reply, ASK_DATE_PROMPT);
  assert.equal(shouldAskKnowledge(started.phase), false);
  assert.equal(routeIdleMessage('What are your clinic hours?'), null);

  const listed = applyAvailableSlots(
    advance(started, 'October 15, 2026'),
    { availability: 'AVAILABLE', slots: [{ ...slot, date: '2026-10-15', startTime: '10:00:00', endTime: '10:30:00', status: 'AVAILABLE' }] },
  );
  const named = advance(advance(listed, '10:00 AM'), 'Vijaya');
  assert.equal(named.phase, BookingPhase.COLLECTING_CONTACT);
  assert.equal(named.draft.patientName, 'Vijaya');
  const confirming = advance(named, '98765-43210');
  assert.match(confirming.reply, /Would you like me to confirm this appointment/);
  assert.equal(confirming.action, null);
  assert.equal(advance(confirming, 'yes').action.type, 'book');
});

test('cancellation collects registered details, handles matches, and requires explicit yes', () => {
  const cancelStart = routeIdleMessage('I want to cancel my appointment.');
  assert.equal(cancelStart.phase, BookingPhase.AWAITING_CANCELLATION_NAME);
  const named = handleBookingMessage(cancelStart, 'Vijaya Pandey', today);
  assert.equal(named.phase, BookingPhase.AWAITING_CANCELLATION_CONTACT);
  assert.equal(handleBookingMessage(cancelStart, 'x', today).phase, BookingPhase.AWAITING_CANCELLATION_NAME);
  assert.equal(handleBookingMessage(named, '123', today).phase, BookingPhase.AWAITING_CANCELLATION_CONTACT);
  const lookup = handleBookingMessage(named, '+91 98765 43210', today);
  assert.equal(lookup.action.type, 'findCancellation');
  const confirming = applyCancellationMatches(lookup, [{
    id: '6d9802e8-6fce-4e1c-9d47-5c6b1f8d1001',
    date: '2026-10-15',
    startTime: '10:00:00',
    endTime: '10:30:00',
    status: 'BOOKED',
    provider: 'Dr. Maya Chen',
  }]);
  assert.equal(confirming.phase, BookingPhase.AWAITING_CANCELLATION_OTP_REQUEST);
  assert.equal(confirming.reply.includes('6d9802e8'), false);
  assert.equal(advance(confirming, 'no').phase, BookingPhase.IDLE);
  const otpRequest = advance(confirming, 'yes');
  assert.equal(otpRequest.action.type, 'requestCancellationOtp');
  const otpEntry = applyCancellationOtpIssued(otpRequest, { requestId: 'request-1', expiresAt: '2030-06-10T00:00:00Z', resendAvailableAt: '2030-06-10T00:01:00Z' });
  assert.equal(otpEntry.phase, BookingPhase.AWAITING_CANCELLATION_OTP);
  assert.equal(advance(otpEntry, '12').phase, BookingPhase.AWAITING_CANCELLATION_OTP);
  assert.equal(advance(otpEntry, 'resend').action.type, 'resendCancellationOtp');
  const verify = advance(otpEntry, '123456');
  assert.equal(verify.action.type, 'verifyCancellationOtp');
  const verified = applyCancellationOtpVerified(verify);
  assert.equal(verified.phase, BookingPhase.AWAITING_CANCELLATION_FINAL_CONFIRMATION);
  assert.equal(advance(verified, 'no').phase, BookingPhase.IDLE);
  assert.equal(advance(verified, 'yes').action.type, 'cancelVerified');

  const multiple = applyCancellationMatches(lookup, [
    { id: 'a', date: '2030-06-10', startTime: '09:00:00', endTime: '09:30:00', status: 'BOOKED', provider: 'Dr. Maya Chen' },
    { id: 'b', date: '2030-06-11', startTime: '10:00:00', endTime: '10:30:00', status: 'BOOKED', provider: 'Dr. Jordan Lee' },
  ]);
  assert.equal(multiple.phase, BookingPhase.SELECTING_CANCELLATION);
  assert.equal(multiple.slotChoices.length, 2);
  assert.match(handleBookingMessage(multiple, '2', today).reply, /June 11/);
  assert.equal(applyCancellationMatches(lookup, []).phase, BookingPhase.AWAITING_CANCELLATION_NAME);

  const move = routeIdleMessage('I want to reschedule my appointment.');
  const found = applyInspectedBooking(
    handleBookingMessage(move, '6d9802e8-6fce-4e1c-9d47-5c6b1f8d1001', today),
    {
      id: '6d9802e8-6fce-4e1c-9d47-5c6b1f8d1001',
      date: '2026-10-15',
      startTime: '10:00:00',
      endTime: '10:30:00',
      status: 'BOOKED',
      provider: 'Dr. Maya Chen',
    },
  );
  const choices = applyAvailableSlots(advance(found, 'October 20, 2026'), {
    availability: 'AVAILABLE',
    slots: [{ ...slot, date: '2026-10-20', status: 'AVAILABLE' }],
  });

  const review = advance(choices, '1');
  assert.equal(review.phase, BookingPhase.AWAITING_RESCHEDULE_CONFIRMATION);
  assert.match(review.reply, /original appointment/);
  assert.equal(advance(review, 'no').action, null);
  const accepted = advance(review, 'yes');
  assert.equal(accepted.action.type, 'reschedule');
  assert.equal(accepted.action.fromSlotId, '6d9802e8-6fce-4e1c-9d47-5c6b1f8d1001');
  assert.equal(accepted.action.toSlotId, slot.id);
});

test('booking drafts do not leak between conversations', () => {
  const first = advance(advance(routeIdleMessage('I want to book a dental appointment.'), 'October 15, 2026'), 'not-a-date');
  const second = routeIdleMessage('I want to book a dental appointment.');
  first.draft.contact = '555-0100';
  assert.equal(second.draft.contact, '');
  assert.equal(second.phase, BookingPhase.ASKING_DATE);
  assert.notEqual(first, second);
});

test('public demo setting hides cancellation and blocks starting or continuing its flow', () => {
  assert.equal(isCancellationEnabled('false'), false);
  assert.equal(isCancellationEnabled(undefined), true);

  const disabledStart = routeIdleMessage('cancel my appointment', isCancellationEnabled('false'));
  assert.equal(disabledStart.phase, BookingPhase.IDLE);
  assert.equal(disabledStart.action, null);
  assert.match(disabledStart.reply, /disabled in this demo/);

  const previouslyStarted = routeIdleMessage('cancel my appointment');
  const disabledContinuation = handleBookingMessage(
    previouslyStarted, 'Ada Lovelace', today, isCancellationEnabled('false'),
  );
  assert.equal(disabledContinuation.phase, BookingPhase.IDLE);
  assert.equal(disabledContinuation.action, null);
  assert.match(disabledContinuation.reply, /disabled in this demo/);
});
