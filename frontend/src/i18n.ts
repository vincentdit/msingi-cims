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
    selectedTitle: 'Congratulations — you have been provisionally selected for',
    selectedText: 'Your selection will be verified by NACTVET. You will receive your admission letter and joining instructions by SMS once verification is complete.',
  },
  offer: {
    title: 'Admission offer', text: 'NACTVET has verified your admission to', verificationNo: 'NACTVET verification number',
    acceptBy: 'Accept by {{date}}', accept: 'Accept offer', decline: 'Decline',
    multipleTitle: 'You were admitted by more than one institution',
    multipleText: 'Enter the confirmation code NACTVET sent you by SMS to confirm that you choose this institution.',
    confirmationCode: 'NACTVET confirmation code', attemptsLeft: '{{n}} attempt(s) left', devCode: 'Development mode — the NACTVET code is {{code}}',
    declineTitle: 'Decline this offer?', declineText: 'Your place will be offered to another applicant. This cannot be undone.',
    declineReason: 'Reason (optional)', confirmDecline: 'Decline offer', cancel: 'Cancel',
    acceptedTitle: 'You have accepted your admission to',
    acceptedText: 'Welcome! Your admission letter and joining instructions will be available here and sent to you by SMS.',
    declinedTitle: 'You declined the offer for', declinedText: 'Thank you for letting us know. We wish you success.',
    lapsedTitle: 'Your offer has lapsed', lapsedText: 'The offer for {{programme}} was not accepted by the deadline. Contact the admissions office if you need help.',
    expiredText: 'The deadline to accept has passed. Please contact the admissions office.',
    rejectedTitle: 'NACTVET did not verify your admission', rejectedText: 'Please contact the admissions office for advice.',
    awaitingVerification: 'Awaiting NACTVET verification',
    downloadLetter: 'Download admission letter', letterPreparing: 'Your admission letter and joining instructions are being prepared. You will receive an SMS when they are ready.',
    letterCode: 'Letter verification code',
    registrationNo: 'Your registration number', registered: 'Registration complete', notYetRegistered: 'Bring your original documents when you report to complete registration.',
  },
  verify: {
    title: 'Verify a document', intro: 'Check that an admission letter issued by this institution is genuine. Enter the code printed on the letter, or scan its QR code.',
    code: 'Verification code', check: 'Verify', notFound: 'No document was found with this code. Check the code, or contact the institution.',
    valid: 'Valid document', superseded: 'Replaced by a newer edition', revoked: 'Revoked by the institution',
    validText: 'This admission letter was issued by the institution and is valid. Check that the details below match the paper.',
    supersededText: 'This edition was replaced by a corrected letter (edition {{n}}). Ask the holder for the current edition.',
    revokedText: 'This letter has been revoked and is no longer valid.',
    holder: 'Name', programme: 'Programme', award: 'Award', intake: 'Academic year and intake', letterNo: 'Letter number',
    issued: 'Issued', reporting: 'Reporting date', nactvet: 'NACTVET verification number', institution: 'Institution', edition: 'edition {{n}}',
    privacy: 'Only the details needed to confirm the document are shown.',
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
    selectedTitle: 'Hongera — umechaguliwa kwa awali kujiunga na',
    selectedText: 'Uchaguzi wako utathibitishwa na NACTVET. Utapokea barua ya kujiunga na maelekezo kwa SMS baada ya uthibitisho kukamilika.',
  },
  offer: {
    title: 'Nafasi ya kujiunga', text: 'NACTVET imethibitisha udahili wako katika', verificationNo: 'Namba ya uthibitisho ya NACTVET',
    acceptBy: 'Kubali kabla ya {{date}}', accept: 'Kubali nafasi', decline: 'Kataa',
    multipleTitle: 'Umedahiliwa na taasisi zaidi ya moja',
    multipleText: 'Weka namba ya uthibitisho uliyotumiwa na NACTVET kwa SMS ili kuthibitisha kuwa umechagua taasisi hii.',
    confirmationCode: 'Namba ya uthibitisho ya NACTVET', attemptsLeft: 'Majaribio {{n}} yamebaki', devCode: 'Hali ya majaribio — namba ya NACTVET ni {{code}}',
    declineTitle: 'Kataa nafasi hii?', declineText: 'Nafasi yako itapewa mwombaji mwingine. Huwezi kubadilisha uamuzi huu.',
    declineReason: 'Sababu (si lazima)', confirmDecline: 'Kataa nafasi', cancel: 'Ghairi',
    acceptedTitle: 'Umekubali kujiunga na',
    acceptedText: 'Karibu! Barua yako ya kujiunga na maelekezo ya kujiunga yatapatikana hapa na kutumwa kwako kwa SMS.',
    declinedTitle: 'Umekataa nafasi ya', declinedText: 'Asante kwa kutujulisha. Tunakutakia mafanikio.',
    lapsedTitle: 'Muda wa nafasi yako umekwisha', lapsedText: 'Nafasi ya {{programme}} haikukubaliwa kabla ya tarehe ya mwisho. Wasiliana na ofisi ya udahili ukihitaji msaada.',
    expiredText: 'Muda wa kukubali umekwisha. Tafadhali wasiliana na ofisi ya udahili.',
    rejectedTitle: 'NACTVET haikuthibitisha udahili wako', rejectedText: 'Tafadhali wasiliana na ofisi ya udahili kwa ushauri.',
    awaitingVerification: 'Inasubiri uthibitisho wa NACTVET',
    downloadLetter: 'Pakua barua ya kujiunga', letterPreparing: 'Barua yako ya kujiunga na maelekezo ya kujiunga vinaandaliwa. Utapokea SMS vikiwa tayari.',
    letterCode: 'Namba ya uthibitisho wa barua',
    registrationNo: 'Namba yako ya usajili', registered: 'Usajili umekamilika', notYetRegistered: 'Leta vyeti vyako halisi utakaporipoti ili kukamilisha usajili.',
  },
  verify: {
    title: 'Thibitisha hati', intro: 'Hakikisha kuwa barua ya kujiunga iliyotolewa na taasisi hii ni halali. Weka namba iliyochapishwa kwenye barua, au skani msimbo wake wa QR.',
    code: 'Namba ya uthibitisho', check: 'Thibitisha', notFound: 'Hakuna hati iliyopatikana kwa namba hii. Hakiki namba, au wasiliana na taasisi.',
    valid: 'Hati halali', superseded: 'Imebadilishwa na toleo jipya', revoked: 'Imefutwa na taasisi',
    validText: 'Barua hii ya kujiunga imetolewa na taasisi na ni halali. Hakikisha taarifa zilizo hapa chini zinalingana na karatasi.',
    supersededText: 'Toleo hili limebadilishwa na barua iliyosahihishwa (toleo {{n}}). Muombe mhusika toleo la sasa.',
    revokedText: 'Barua hii imefutwa na si halali tena.',
    holder: 'Jina', programme: 'Programu', award: 'Tuzo', intake: 'Mwaka wa masomo na muhula', letterNo: 'Namba ya barua',
    issued: 'Imetolewa', reporting: 'Tarehe ya kuripoti', nactvet: 'Namba ya uthibitisho ya NACTVET', institution: 'Taasisi', edition: 'toleo {{n}}',
    privacy: 'Ni taarifa zinazohitajika kuthibitisha hati pekee ndizo zinazoonyeshwa.',
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
