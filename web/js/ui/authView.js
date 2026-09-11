/**
 * Port of ParentAuthFeature.kt (AuthScreen) and the MissingFirebaseScreen.
 */
import { h, field } from './components.js';
import { signIn, createAccount, loadFirebaseMessage } from '../store.js';

export function renderMissingFirebaseView(state) {
  const wrap = h('div', { class: 'auth-wrap' });
  const message = state.firebaseMessage ?? 'Add the live Firebase web config as js/config.local.js (copy js/config.example.js) and reload.';
  wrap.append(h('div', { class: 'card auth-card' },
    h('div', { class: 'shield' }, '🛡️'),
    h('div', { class: 'card-title' }, 'Firebase not configured'),
    h('div', { class: 'small muted' }, message),
  ));
  loadFirebaseMessage().then((error) => {
    if (error) {
      const body = wrap.querySelector('.small.muted');
      if (body) body.textContent = `${message} (${error})`;
    }
  });
  return wrap;
}

export function renderAuthView(state) {
  const emailInput = h('input', {
    type: 'email', placeholder: 'name@example.com', autocomplete: 'username',
    'data-persist-key': 'auth-email',
  });
  const passwordInput = h('input', {
    type: 'password', placeholder: 'Password', autocomplete: 'current-password',
    'data-persist-key': 'auth-password',
  });
  let showPassword = false;
  const toggle = h('button', {
    class: 'textbtn small', type: 'button',
    onClick: () => {
      showPassword = !showPassword;
      passwordInput.type = showPassword ? 'text' : 'password';
      toggle.textContent = showPassword ? 'Hide' : 'Show';
    },
  }, 'Show');

  const submit = () => {
    if (state.authBusy) return;
    if (mode === 'signin') signIn(emailInput.value, passwordInput.value);
    else createAccount(emailInput.value, passwordInput.value);
  };
  emailInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') submit(); });
  passwordInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') submit(); });

  let mode = 'signin';
  const actionButton = h('button', {
    class: 'btn', disabled: state.authBusy,
    onClick: submit,
  }, state.authBusy ? 'Working...' : 'Sign In');

  const switchLink = h('button', {
    class: 'textbtn', type: 'button',
    onClick: () => {
      mode = mode === 'signin' ? 'create' : 'signin';
      actionButton.textContent = state.authBusy ? 'Working...' : (mode === 'signin' ? 'Sign In' : 'Create Account');
      switchLink.textContent = mode === 'signin' ? 'Create Account' : 'Have an account? Sign In';
    },
  }, 'Create Account');

  return h('div', { class: 'auth-wrap' },
    h('div', { class: 'card auth-card' },
      h('div', { class: 'shield' }, '🛡️'),
      h('div', { class: 'card-title' }, 'GuardPulse'),
      h('div', { class: 'small muted' }, state.message ?? 'Connect to your Firebase project to get started.'),
      field('Email', emailInput),
      field('Password', h('div', { class: 'row' }, passwordInput, toggle)),
      actionButton,
      h('div', { class: 'row', style: { justifyContent: 'center' } }, switchLink),
    ),
  );
}
