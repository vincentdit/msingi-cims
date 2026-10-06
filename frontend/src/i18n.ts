import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';

// English and Kiswahili (BR-40). Keys are grouped by screen.
const en = {
  nav: { apply: 'Apply', signIn: 'Sign in', signOut: 'Sign out', myApplication: 'My application', staff: 'Staff' },
  home: {
    heroTitle: 'Apply online for {{year}}',
    heroText: 'Certificate and diploma programmes accredited by NACTVET. Apply from your phone in about 20 minutes.',
    startApplication: 'Start your application',
    noWindow: 'Admissions are currently closed. Please check again later.',
    closes: 'Applications close on {{date}}',
    fee: 'Application fee',
    programmes: 'Programmes on offer',
    requirements: 'Minimum entry requirements',
    places: '{{n}} places',
    howTitle: 'How to apply',
    how1: 'Create an account with your mobile number',
    how2: 'Enter your NECTA index number — results are fetched automatically',
    how3: 'Pay the application fee with a GePG control number',
    how4: 'Choose up to {{n}} programmes you qualify for and submit',
  },
  auth: {
    registerTitle: 'Create your applicant account',
    firstName: 'First name', middleName: 'Middle name (optional)', surname: 'Surname',
    phone: 'Mobile number', email: 'Email (optional)', password: 'Password',
    passwordHelp: 'At least 8 characters with letters and numbers',
    privacy: 'I have read the privacy notice and agree that my data is used to process my application.',
    create: 'Create account', haveAccount: 'Already have an account? Sign in',
    verifyTitle: 'Verify your phone', verifyText: 'Enter the code we sent by SMS to {{phone}}.',
    code: 'Verification code', verify: 'Verify', resend: 'Send a new code',
    loginTitle: 'Sign in to continue your application', login: 'Mobile number or email', signIn: 'Sign in',
    noAccount: 'New applicant? Create an account', forgot: 'Forgot password?',
    forgotTitle: 'Reset your password', sendCode: 'Send reset code', newPassword: 'New password', reset: 'Change password',
    devCode: 'Development mode — your code is {{code}}',
  },
  app: {
    title: 'My application', steps: { results: 'Results', details: 'Personal details', fee: 'Application fee', programmes: 'Programmes', review: 'Review & submit' },
    resultsIntro: 'Enter your NECTA index number. Your results are fetched and verified automatically.',
    examType: 'Examination', indexNumber: 'Index number', year: 'Year', fetch: 'Fetch results',
    priorAward: 'I hold an NTA or NVA award', awardNumber: 'AVN / certificate number', level: 'Level', add: 'Add',
    verified: 'Verified', pending: 'Pending verification', remove: 'Remove',
    save: 'Save', saved: 'Saved', next: 'Continue', back: 'Back',
    feeIntro: 'Pay {{amount}} using the control number below through your bank or mobile money (M-Pesa, Tigo Pesa, Airtel Money, HaloPesa).',
    getControlNo: 'Get control number', controlNo: 'Control number', expires: 'Valid until {{date}}',
    feePaid: 'Application fee paid', simulate: 'Simulate payment (development)', checkPayment: 'I have paid — check status',
    programmesIntro: 'Choose up to {{n}} programmes in order of preference. Only programmes you qualify for can be selected.',
    eligible: 'You qualify', notEligible: 'Not eligible', why: 'Why?', choice: 'Choice {{n}}',
    saveChoices: 'Save choices', reviewIntro: 'Check your application before submitting. You cannot change it afterwards.',
    submit: 'Submit application', submitted: 'Application submitted', reference: 'Reference number',
    submittedText: 'We have received your application. You will be notified by SMS when selection results are out.',
    todo: 'Still to do',
  },
  common: { loading: 'Loading…', error: 'Something went wrong', retry: 'Try again', yes: 'Yes', no: 'No', poweredBy: 'Powered by Msingi Technologies Ltd' },
};

const sw: typeof en = {
  nav: { apply: 'Omba', signIn: 'Ingia', signOut: 'Toka', myApplication: 'Maombi yangu', staff: 'Watumishi' },
  home: {
    heroTitle: 'Omba mtandaoni kwa {{year}}',
    heroText: 'Programu za astashahada na stashahada zilizoidhinishwa na NACTVET. Omba kwa simu yako kwa takriban dakika 20.',
    startApplication: 'Anza maombi yako',
    noWindow: 'Udahili umefungwa kwa sasa. Tafadhali angalia tena baadaye.',
    closes: 'Maombi yanafungwa tarehe {{date}}',
    fee: 'Ada ya maombi',
    programmes: 'Programu zinazotolewa',
    requirements: 'Sifa za chini za kujiunga',
    places: 'Nafasi {{n}}',
    howTitle: 'Jinsi ya kuomba',
    how1: 'Fungua akaunti kwa namba yako ya simu',
    how2: 'Weka namba yako ya mtihani wa NECTA — matokeo yanapatikana yenyewe',
    how3: 'Lipa ada ya maombi kwa namba ya malipo ya GePG',
    how4: 'Chagua hadi programu {{n}} unazostahili kisha wasilisha',
  },
  auth: {
    registerTitle: 'Fungua akaunti ya mwombaji',
    firstName: 'Jina la kwanza', middleName: 'Jina la kati (si lazima)', surname: 'Jina la ukoo',
    phone: 'Namba ya simu', email: 'Barua pepe (si lazima)', password: 'Nenosiri',
    passwordHelp: 'Angalau herufi 8 zenye herufi na namba',
    privacy: 'Nimesoma taarifa ya faragha na nakubali taarifa zangu zitumike kushughulikia maombi yangu.',
    create: 'Fungua akaunti', haveAccount: 'Una akaunti tayari? Ingia',
    verifyTitle: 'Thibitisha simu yako', verifyText: 'Weka namba tuliyotuma kwa SMS kwenda {{phone}}.',
    code: 'Namba ya uthibitisho', verify: 'Thibitisha', resend: 'Tuma namba mpya',
    loginTitle: 'Ingia kuendelea na maombi yako', login: 'Namba ya simu au barua pepe', signIn: 'Ingia',
    noAccount: 'Mwombaji mpya? Fungua akaunti', forgot: 'Umesahau nenosiri?',
    forgotTitle: 'Badilisha nenosiri', sendCode: 'Tuma namba', newPassword: 'Nenosiri jipya', reset: 'Badilisha nenosiri',
    devCode: 'Hali ya majaribio — namba yako ni {{code}}',
  },
  app: {
    title: 'Maombi yangu', steps: { results: 'Matokeo', details: 'Taarifa binafsi', fee: 'Ada ya maombi', programmes: 'Programu', review: 'Hakiki na wasilisha' },
    resultsIntro: 'Weka namba yako ya mtihani wa NECTA. Matokeo yako yanapatikana na kuthibitishwa yenyewe.',
    examType: 'Mtihani', indexNumber: 'Namba ya mtihani', year: 'Mwaka', fetch: 'Pata matokeo',
    priorAward: 'Nina tuzo ya NTA au NVA', awardNumber: 'AVN / namba ya cheti', level: 'Ngazi', add: 'Ongeza',
    verified: 'Imethibitishwa', pending: 'Inasubiri uthibitisho', remove: 'Ondoa',
    save: 'Hifadhi', saved: 'Imehifadhiwa', next: 'Endelea', back: 'Rudi',
    feeIntro: 'Lipa {{amount}} kwa kutumia namba ya malipo hapa chini kupitia benki au simu (M-Pesa, Tigo Pesa, Airtel Money, HaloPesa).',
    getControlNo: 'Pata namba ya malipo', controlNo: 'Namba ya malipo', expires: 'Inatumika hadi {{date}}',
    feePaid: 'Ada ya maombi imelipwa', simulate: 'Iga malipo (majaribio)', checkPayment: 'Nimelipa — angalia hali',
    programmesIntro: 'Chagua hadi programu {{n}} kwa mpangilio wa upendeleo. Ni programu unazostahili tu zinazoweza kuchaguliwa.',
    eligible: 'Unastahili', notEligible: 'Hustahili', why: 'Kwa nini?', choice: 'Chaguo {{n}}',
    saveChoices: 'Hifadhi machaguo', reviewIntro: 'Hakiki maombi yako kabla ya kuwasilisha. Huwezi kuyabadilisha baadaye.',
    submit: 'Wasilisha maombi', submitted: 'Maombi yamewasilishwa', reference: 'Namba ya kumbukumbu',
    submittedText: 'Tumepokea maombi yako. Utajulishwa kwa SMS matokeo ya uchaguzi yatakapotoka.',
    todo: 'Bado kukamilisha',
  },
  common: { loading: 'Inapakia…', error: 'Hitilafu imetokea', retry: 'Jaribu tena', yes: 'Ndiyo', no: 'Hapana', poweredBy: 'Imewezeshwa na Msingi Technologies Ltd' },
};

const saved = (() => {
  try {
    return localStorage.getItem('cims.lang');
  } catch {
    return null;
  }
})();

i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, sw: { translation: sw } },
  lng: saved ?? 'en',
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
});

export function setLanguage(lng: 'en' | 'sw') {
  i18n.changeLanguage(lng);
  try {
    localStorage.setItem('cims.lang', lng);
  } catch {
    /* storage unavailable */
  }
}

export default i18n;
