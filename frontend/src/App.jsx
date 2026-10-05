import { useEffect, useRef, useState } from 'react';
import { applyTheme, persistTheme, readSavedTheme, resolveTheme } from './lib/theme.js';
import { isCancellationEnabled } from './lib/demoSettings.js';
import {
  applyAvailableSlots,
  applyCancellationMatches,
  applyCancellationOtpIssued,
  applyCancellationOtpVerified,
  applyInspectedBooking,
  bookingFailure,
  bookingSucceeded,
  clinicToday,
  handleBookingMessage,
  initialBookingConversation,
  isBookingInProgress,
  routeIdleMessage,
  shouldAskKnowledge,
  startDateSelection,
} from './lib/bookingConversation.js';
import {
  askMolarAI,
  KnowledgeUnavailableError,
  bookAppointment,
  cancelAppointment,
  fetchAppointmentSlot,
  fetchAvailableSlots,
  rescheduleAppointment,
  findCancellationMatches,
  requestCancellationOtp,
  resendCancellationOtp,
  verifyCancellationOtp,
  cancelVerifiedAppointment,
} from './lib/knowledgeApi.js';

const suggestions = [
  'What services do you offer?',
  'Do you accept Aetna insurance?',
  'What are your clinic hours?',
  'Do you have appointments available on June 10, 2030?',
];
const cancellationEnabled = isCancellationEnabled();

function ToothMark() {
  return (
    <svg viewBox="0 0 40 40" aria-hidden="true" className="tooth-mark">
      <rect width="40" height="40" rx="13" fill="currentColor" />
      <path d="M12.4 14.1c1.1-2.1 3.1-2.5 5.4-1.6 1.5.6 2.9.6 4.5 0 2.4-.9 4.6-.4 5.5 1.8 1.2 2.8-.4 6.2-1.6 9.2-.8 2.1-1.7 4.7-3.2 4.7-1.2 0-1.4-2.1-2.1-4.2-.4-1.3-1.7-1.3-2.1 0-.7 2.1-.9 4.2-2.1 4.2-1.5 0-2.4-2.6-3.2-4.7-1.2-3-2.6-6.5-1.1-9.4Z" fill="none" stroke="white" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M15.2 15.5c1-.7 2.1-.8 3.1-.3" fill="none" stroke="white" strokeWidth="1.4" strokeLinecap="round" />
    </svg>
  );
}

function AssistantAvatar({ small = false }) {
  return (
    <span className={`assistant-avatar${small ? ' assistant-avatar--small' : ''}`} aria-hidden="true">
      <svg viewBox="0 0 24 24" fill="none">
        <path d="M7.3 8.3c.7-1.2 2-1.5 3.3-1 1 .4 1.8.4 2.8 0 1.4-.5 2.7-.2 3.4 1.1.8 1.7-.2 3.8-1 5.6-.5 1.3-1 2.9-2 2.9-.8 0-.9-1.3-1.4-2.6-.3-.8-1.1-.8-1.4 0-.5 1.3-.6 2.6-1.4 2.6-1 0-1.5-1.6-2-2.9-.8-1.8-1.7-4-.3-5.7Z" stroke="currentColor" strokeWidth="1.45" strokeLinecap="round" strokeLinejoin="round" />
        <path d="M8.8 9.1c.6-.4 1.2-.5 1.9-.2" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" />
      </svg>
    </span>
  );
}

function SourceList({ sources, answerSource }) {
  if (answerSource === 'live_availability') {
    return (
      <div className="sources live-availability-source" aria-label="Answer checked against live appointment availability">
        <span className="source-dot" aria-hidden="true" />
        <span>Live availability</span>
      </div>
    );
  }
  if (!sources?.length) return null;
  return (
    <details className="sources">
      <summary>
        <span className="sources-icon" aria-hidden="true">⌕</span>
        Sources <span className="source-count">{sources.length}</span>
      </summary>
      <ul>
        {sources.map((source, index) => (
          <li key={`${source.documentId ?? source.documentName}-${source.chunkIndex ?? index}`}>
            <span className="source-dot" aria-hidden="true" />
            <span>{source.documentName || 'Clinic information'}</span>
          </li>
        ))}
      </ul>
    </details>
  );
}

function currentTheme() {
  const active = document.documentElement.getAttribute('data-theme');
  if (active === 'light' || active === 'dark') return active;
  return resolveTheme(readSavedTheme(), window.matchMedia('(prefers-color-scheme: dark)').matches);
}

function ThemeToggle() {
  const [theme, setTheme] = useState(currentTheme);
  const nextTheme = theme === 'dark' ? 'light' : 'dark';

  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  function toggleTheme() {
    persistTheme(nextTheme);
    setTheme(nextTheme);
  }

  return (
    <button
      className="theme-toggle"
      type="button"
      onClick={toggleTheme}
      aria-pressed={theme === 'dark'}
      aria-label={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
    >
      {theme === 'dark' ? (
        <svg viewBox="0 0 20 20" fill="none" aria-hidden="true">
          <circle cx="10" cy="10" r="3.2" stroke="currentColor" strokeWidth="1.6" />
          <path d="M10 2.4v1.8M10 15.8v1.8M2.4 10h1.8M15.8 10h1.8M4.4 4.4l1.3 1.3M14.3 14.3l1.3 1.3M15.6 4.4l-1.3 1.3M5.7 14.3l-1.3 1.3" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
        </svg>
      ) : (
        <svg viewBox="0 0 20 20" fill="none" aria-hidden="true">
          <path d="M16.2 12.4A6.2 6.2 0 0 1 7.6 3.8 6.4 6.4 0 1 0 16.2 12.4Z" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
        </svg>
      )}
    </button>
  );
}

function LoadingIndicator({ label = 'Finding a helpful answer…' }) {
  return (
    <div className="message-row assistant-row" role="status" aria-label="MolarAI is thinking">
      <AssistantAvatar small />
      <div className="loading-bubble" aria-hidden="true">
        <span /><span /><span />
      </div>
      <span className="loading-label">{label}</span>
    </div>
  );
}

export default function App() {
  const [messages, setMessages] = useState([]);
  const [draft, setDraft] = useState('');
  const [isSending, setIsSending] = useState(false);
  const [error, setError] = useState('');
  const [bookingConversation, setBookingConversation] = useState(initialBookingConversation);
  const bookingPhase = bookingConversation.phase;
  const bookingRef = useRef(bookingConversation);
  bookingRef.current = bookingConversation;
  const requestInFlight = useRef(false);
  const requestSequence = useRef(0);
  const conversationEnd = useRef(null);
  const inputRef = useRef(null);

  useEffect(() => {
    conversationEnd.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages, isSending]);

  async function sendQuestion(question = draft) {
    const query = question.trim();
    if (!query || isSending || requestInFlight.current) return;

    const requestId = ++requestSequence.current;
    requestInFlight.current = true;
    setMessages((current) => [...current, { id: crypto.randomUUID(), role: 'user', text: query }]);
    setDraft('');
    setError('');
    setIsSending(true);

    try {
      if (!shouldAskKnowledge(bookingRef.current.phase)) {
        await continueBooking(query, requestId);
        return;
      }
      const started = routeIdleMessage(query, cancellationEnabled);
      if (started) {
        if (requestId !== requestSequence.current) return;
        rememberBooking(started);
        addAssistantMessage(started.reply);
        return;
      }
      const response = await askMolarAI(query);
      if (requestId !== requestSequence.current) return;
      setMessages((current) => [...current, {
        id: crypto.randomUUID(),
        role: 'assistant',
        text: response.answer,
        sources: response.sources,
        answerSource: response.answerSource,
      }]);
    } catch (requestError) {
      if (requestId === requestSequence.current) {
        setError(requestError instanceof KnowledgeUnavailableError
          ? requestError.message
          : 'I couldn’t reach MolarAI just now. Please check that the assistant service is running, then try again.');
      }
    } finally {
      if (requestId === requestSequence.current) {
        requestInFlight.current = false;
        setIsSending(false);
        inputRef.current?.focus();
      }
    }
  }

  function goToMenu() {
    requestSequence.current += 1;
    requestInFlight.current = false;
    setMessages([]);
    setDraft('');
    setError('');
    setIsSending(false);
    const reset = initialBookingConversation();
    bookingRef.current = reset;
    setBookingConversation(reset);
  }

  function rememberBooking(next) {
    bookingRef.current = next;
    setBookingConversation(next);
  }

  function addAssistantMessage(text, extra = {}) {
    setMessages((current) => [...current, {
      id: crypto.randomUUID(),
      role: 'assistant',
      text,
      ...extra,
    }]);
  }

  async function continueBooking(query, requestId) {
    const step = handleBookingMessage(bookingRef.current, query, clinicToday(), cancellationEnabled);
    if (step.action?.type === 'lookup') {
      rememberBooking(step);
      try {
        const slots = await fetchAvailableSlots(step.action.date);
        if (requestId !== requestSequence.current) return;
        const listed = applyAvailableSlots(step, slots);
        rememberBooking(listed);
        addAssistantMessage(listed.reply, {
          answerSource: 'live_availability',
          slotChoices: listed.slotChoices,
          listingId: listed.listingId,
        });
      } catch {
        if (requestId !== requestSequence.current) return;
        const failed = {
          ...step,
          action: null,
          reply: 'I could not check appointments for that date. Please try the date again.',
        };
        rememberBooking(failed);
        addAssistantMessage(failed.reply);
      }
      return;
    }

    if (step.action?.type === 'book') {
      rememberBooking(step);
      try {
        const booked = await bookAppointment(step.action.body);
        if (requestId !== requestSequence.current) return;
        const done = bookingSucceeded(step, booked);
        rememberBooking(done);
        addAssistantMessage(`${done.reply} ${formatBookedDetails(booked)}`);
      } catch (error) {
        if (requestId !== requestSequence.current) return;
        await recoverBookingFailure(step, error, requestId);
      }
      return;
    }

    if (step.action?.type === 'findCancellation') {
      rememberBooking(step);
      try {
        const matches = await findCancellationMatches(step.action.body.patientName, step.action.body.patientContact);
        if (requestId !== requestSequence.current) return;
        const listed = applyCancellationMatches(step, matches);
        rememberBooking(listed);
        addAssistantMessage(listed.reply, { slotChoices: listed.slotChoices, listingId: listed.listingId });
      } catch (error) {
        if (requestId !== requestSequence.current) return;
        addAssistantMessage(error?.status === 400
          ? 'Please check the registered name and phone number, then try again.'
          : 'I could not look up that appointment. Please try again.');
        rememberBooking({ ...step, action: null });
      }
      return;
    }

    if (step.action?.type === 'requestCancellationOtp') {
      rememberBooking(step);
      try {
        const issued = await requestCancellationOtp({
          appointmentSlotId: step.action.slotId,
          patientName: step.action.patientName,
          patientContact: step.action.patientContact,
        });
        if (requestId !== requestSequence.current) return;
        const next = applyCancellationOtpIssued(step, issued);
        rememberBooking(next);
        addAssistantMessage(next.reply);
      } catch (error) {
        if (requestId !== requestSequence.current) return;
        addAssistantMessage(error?.status === 409
          ? 'Please wait before requesting another verification code.'
          : 'I could not start cancellation verification. Please try again.');
        rememberBooking({ ...step, action: null });
      }
      return;
    }

    if (step.action?.type === 'resendCancellationOtp') {
      rememberBooking(step);
      try {
        const issued = await resendCancellationOtp(step.action.requestId);
        if (requestId !== requestSequence.current) return;
        const next = applyCancellationOtpIssued(step, issued);
        rememberBooking(next);
        addAssistantMessage(next.reply);
      } catch (error) {
        if (requestId !== requestSequence.current) return;
        addAssistantMessage(error?.status === 409
          ? 'That code cannot be resent yet. Please wait a little longer or start over.'
          : 'I could not resend the verification code. Please try again.');
        rememberBooking({ ...step, action: null });
      }
      return;
    }

    if (step.action?.type === 'verifyCancellationOtp') {
      rememberBooking(step);
      try {
        await verifyCancellationOtp(step.action.requestId, step.action.otp);
        if (requestId !== requestSequence.current) return;
        const next = applyCancellationOtpVerified(step);
        rememberBooking(next);
        addAssistantMessage(next.reply);
      } catch (error) {
        if (requestId !== requestSequence.current) return;
        addAssistantMessage(error?.status === 409
          ? 'That code is invalid or expired. Please try again or request a new code.'
          : 'I could not verify that code. Please try again.');
        rememberBooking({ ...step, action: null });
      }
      return;
    }

    if (step.action?.type === 'cancelVerified') {
      rememberBooking(step);
      try {
        await cancelVerifiedAppointment(step.action.requestId);
        if (requestId !== requestSequence.current) return;
        const done = initialBookingConversation();
        rememberBooking(done);
        addAssistantMessage('Your appointment is cancelled. That time is open again.');
      } catch (error) {
        if (requestId !== requestSequence.current) return;
        addAssistantMessage(error?.status === 409
          ? 'That appointment is no longer booked, so it was not cancelled again.'
          : 'I could not cancel that appointment. Please try again.');
        rememberBooking({ ...step, action: null });
      }
      return;
    }

    if (step.action?.type === 'inspect') {
      try {
        const slot = await fetchAppointmentSlot(step.action.slotId);
        if (requestId !== requestSequence.current) return;
        const inspected = applyInspectedBooking(step, slot);
        rememberBooking(inspected);
        if (inspected.reply) addAssistantMessage(inspected.reply);
      } catch (error) {
        if (requestId !== requestSequence.current) return;
        const missing = error?.status === 404;
        addAssistantMessage(missing
          ? 'I could not find an appointment with that reference. Please check it and try again.'
          : 'I could not look up that reference. Please try again.');
      }
      return;
    }

    if (step.action?.type === 'cancel') {
      try {
        await cancelAppointment(step.action.slotId);
        if (requestId !== requestSequence.current) return;
        const done = initialBookingConversation();
        rememberBooking(done);
        addAssistantMessage('Your appointment is cancelled. That time is open again.');
      } catch (error) {
        if (requestId !== requestSequence.current) return;
        addAssistantMessage(error?.status === 409
          ? 'That appointment is no longer booked, so it was not cancelled again.'
          : 'I could not cancel that appointment. Please try again.');
      }
      return;
    }

    if (step.action?.type === 'reschedule') {
      try {
        const moved = await rescheduleAppointment(step.action.fromSlotId, step.action.toSlotId);
        if (requestId !== requestSequence.current) return;
        const done = initialBookingConversation();
        rememberBooking(done);
        addAssistantMessage(`Your appointment has been rescheduled. Reference: ${moved.id}. ${formatBookedDetails(moved)}`);
      } catch (error) {
        if (requestId !== requestSequence.current) return;
        addAssistantMessage(error?.status === 409
          ? 'That new time was just booked. Your original appointment is unchanged. Please choose another time.'
          : 'I could not reschedule that appointment. Your original appointment is unchanged.');
      }
      return;
    }

    rememberBooking(step);
    if (step.reply) addAssistantMessage(step.reply);
  }

  function chooseListedSlot(listingId, index) {
    if (listingId !== bookingRef.current.listingId || isSending || requestInFlight.current) return;
    sendQuestion(String(index + 1));
  }

  function beginBooking() {
    if (isSending || requestInFlight.current || isBookingInProgress(bookingPhase)) return;
    const offered = startDateSelection();
    rememberBooking(offered);
    setError('');
    addAssistantMessage(offered.reply);
  }

  async function recoverBookingFailure(step, error, requestId) {
    const failed = bookingFailure(step, error?.status);
    if (failed.reply) addAssistantMessage(failed.reply);
    if (failed.action?.type !== 'lookup') {
      rememberBooking(failed);
      return;
    }
    try {
      const slots = await fetchAvailableSlots(failed.action.date);
      if (requestId !== requestSequence.current) return;
      const listed = applyAvailableSlots(failed, slots);
      rememberBooking(listed);
      if (listed.reply) {
        addAssistantMessage(listed.reply, {
          answerSource: 'live_availability',
          slotChoices: listed.slotChoices,
          listingId: listed.listingId,
        });
      }
    } catch {
      if (requestId !== requestSequence.current) return;
      rememberBooking({ ...failed, action: null });
    }
  }

  function formatBookedDetails(slot) {
    if (!slot?.date) return '';
    const provider = slot.provider ? ` with ${slot.provider}` : '';
    return `${slot.date} ${slot.startTime || ''}–${slot.endTime || ''}${provider}`.trim();
  }

  function handleSubmit(event) {
    event.preventDefault();
    sendQuestion();
  }

  function handleKeyDown(event) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      sendQuestion();
    }
  }

  return (
    <div className="app-shell">
      <header className="site-header">
        <a className="brand" href="#home" aria-label="MolarAI home">
          <ToothMark />
          <span className="brand-copy">
            <strong>Molar<span>AI</span></strong>
            <small>Dental support, made simple</small>
          </span>
        </a>
        <div className="header-meta">
          <span className="header-subtitle">AI Dental Support Assistant</span>
          <ThemeToggle />
          <span className="availability-pill"><i />Here to help</span>
        </div>
      </header>

      <main id="home" className="main-layout">
        <aside className="intro-panel">
          <div className="eyebrow"><span /> YOUR DENTAL CARE, A LITTLE CLEARER</div>
          <h1>Good questions<br />make for <em>healthy</em><br />smiles.</h1>
          <p className="intro-copy">Get clear, friendly answers about care, coverage, and finding a time to visit.</p>

          <div className="help-card">
            <div className="help-card-heading">
              <span className="sparkle-icon" aria-hidden="true">✳</span>
              <span>How I can help</span>
            </div>
            <div className="help-item">
              <span className="help-icon help-icon--blue" aria-hidden="true">
                <svg viewBox="0 0 20 20" fill="none"><path d="M4 5.5h12v8H9l-3.5 2v-2H4v-8Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" /><path d="M7 8.5h6M7 10.8h4" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" /></svg>
              </span>
              <span><strong>Clinic questions</strong><small>Services, hours &amp; insurance</small></span>
            </div>
            <div className="help-item">
              <span className="help-icon help-icon--mint" aria-hidden="true">
                <svg viewBox="0 0 20 20" fill="none"><rect x="3.5" y="5" width="13" height="11" rx="2" stroke="currentColor" strokeWidth="1.4" /><path d="M6.5 3.5v3M13.5 3.5v3M3.5 8.5h13M7 11h2v2H7z" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" /></svg>
              </span>
              <span><strong>Live availability</strong><small>Appointments for a date or time</small></span>
            </div>
          </div>

          <div className="privacy-note">
            <span className="privacy-check" aria-hidden="true">✓</span>
            <p>Friendly guidance, grounded in MolarAI clinic information.</p>
          </div>
        </aside>

        <section className="chat-card" aria-label="Chat with MolarAI">
          <div className="chat-topbar">
            <div className="chat-agent">
              <AssistantAvatar />
              <span><strong>MolarAI Assistant</strong><small><i />Typically replies in a few seconds</small></span>
            </div>
            <div className="chat-topbar-actions">
              <button className="menu-button" type="button" onClick={goToMenu} aria-label="Go to menu and clear this conversation">
                <svg viewBox="0 0 18 18" fill="none" aria-hidden="true"><path d="M3 5h12M3 9h12M3 13h12" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" /></svg>
                Go to menu
              </button>
              <span className="secure-label"><svg viewBox="0 0 16 16" fill="none" aria-hidden="true"><rect x="3" y="7" width="10" height="7" rx="1.6" stroke="currentColor" strokeWidth="1.3"/><path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round"/></svg>Private conversation</span>
            </div>
          </div>

          <div className="conversation" aria-live="polite">
            <div className="day-divider"><span>Today</span></div>
            <div className="message-row assistant-row">
              <AssistantAvatar small />
              <div className="message-stack">
                <span className="sender-label">MolarAI</span>
                <div className="message-bubble assistant-bubble">
                  <p>Hi there! I’m MolarAI, your dental support assistant. What can I help you with today?</p>
                </div>
                <time className="message-time">Just now</time>
              </div>
            </div>

            {messages.map((message) => (
              <div className={`message-row ${message.role === 'user' ? 'user-row' : 'assistant-row'}`} key={message.id}>
                {message.role === 'assistant' && <AssistantAvatar small />}
                <div className="message-stack">
                  {message.role === 'assistant' && <span className="sender-label">MolarAI</span>}
                  <div className={`message-bubble ${message.role === 'user' ? 'user-bubble' : 'assistant-bubble'}`}>
                    <p>{message.text}</p>
                  </div>
                  {message.role === 'assistant' && message.slotChoices?.length > 0 && (
                    <div className="slot-choices">
                      {message.slotChoices.map((choice) => (
                        <button
                          type="button"
                          className="suggestion-chip"
                          key={`${message.listingId}-${choice.index}`}
                          onClick={() => chooseListedSlot(message.listingId, choice.index)}
                        >
                          <span className="suggestion-number">{String(choice.index + 1).padStart(2, '0')}</span>
                          <span>{choice.label}</span>
                          <span className="suggestion-arrow" aria-hidden="true">↗</span>
                        </button>
                      ))}
                    </div>
                  )}
                  {message.role === 'assistant' && <SourceList sources={message.sources} answerSource={message.answerSource} />}
                </div>
              </div>
            ))}

            {isSending && <LoadingIndicator label={shouldAskKnowledge(bookingPhase) ? 'Finding a helpful answer…' : 'Checking that appointment…'} />}
            {error && (
              <div className="error-note" role="alert">
                <span aria-hidden="true">!</span>
                <p>{error}</p>
                <button type="button" onClick={() => sendQuestion(messages.at(-1)?.text || draft)}>Try again</button>
              </div>
            )}

            {messages.length === 0 && !isSending && (
              <div className="suggestions">
                <span className="suggestion-label">A few things you can ask</span>
                <div className="suggestion-grid">
                  <button type="button" className="suggestion-chip" onClick={beginBooking} disabled={isBookingInProgress(bookingPhase)}>
                    <span className="suggestion-number">01</span>
                    <span>Book an appointment</span>
                    <span className="suggestion-arrow" aria-hidden="true">↗</span>
                  </button>
                  {cancellationEnabled && <button type="button" className="suggestion-chip" onClick={() => { const started = routeIdleMessage('cancel my appointment', cancellationEnabled); rememberBooking(started); addAssistantMessage(started.reply); }} disabled={isBookingInProgress(bookingPhase)}>
                    <span className="suggestion-number">02</span>
                    <span>Cancel an appointment</span>
                    <span className="suggestion-arrow" aria-hidden="true">↗</span>
                  </button>}
                  {suggestions.map((question, index) => (
                    <button type="button" className="suggestion-chip" key={question} onClick={() => sendQuestion(question)}>
                    <span className="suggestion-number">0{index + (cancellationEnabled ? 3 : 2)}</span>
                      <span>{question}</span>
                      <span className="suggestion-arrow" aria-hidden="true">↗</span>
                    </button>
                  ))}
                </div>
              </div>
            )}
            {messages.length > 0 && !isSending && !isBookingInProgress(bookingPhase) && (
              <div className="suggestions">
                <div className="suggestion-grid">
                  <button type="button" className="suggestion-chip" onClick={beginBooking}>
                    <span className="suggestion-number">01</span>
                    <span>Book an appointment</span>
                    <span className="suggestion-arrow" aria-hidden="true">↗</span>
                  </button>
                  {cancellationEnabled && <button type="button" className="suggestion-chip" onClick={() => { const started = routeIdleMessage('cancel my appointment', cancellationEnabled); rememberBooking(started); addAssistantMessage(started.reply); }}>
                    <span className="suggestion-number">02</span>
                    <span>Cancel an appointment</span>
                    <span className="suggestion-arrow" aria-hidden="true">↗</span>
                  </button>}
                </div>
              </div>
            )}
            <div ref={conversationEnd} />
          </div>

          <form className="composer" onSubmit={handleSubmit}>
            <label className="visually-hidden" htmlFor="chat-input">Ask MolarAI a question</label>
            <textarea
              id="chat-input"
              ref={inputRef}
              rows={1}
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="Ask a question about your dental care…"
              disabled={isSending}
            />
            <div className="composer-footer">
              <span className="input-hint"><kbd>↵</kbd> to send <span>·</span> <kbd>⇧ ↵</kbd> for a new line</span>
              <button className="send-button" type="submit" disabled={isSending || !draft.trim()}>
                {isSending ? 'Sending' : 'Send'}
                <svg viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M3.5 10h12m-5-5 5 5-5 5" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" /></svg>
              </button>
            </div>
          </form>
          <p className="disclaimer">MolarAI offers general clinic information and does not replace professional dental advice.</p>
        </section>
      </main>

      <footer className="site-footer"><span>© {new Date().getFullYear()} MolarAI Dental Studio</span><span>Thoughtful care starts with a conversation.</span></footer>
    </div>
  );
}
