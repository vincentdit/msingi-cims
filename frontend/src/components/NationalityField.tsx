import { Autocomplete, TextField } from '@mui/material';

/** Tanzania first, then its neighbours and the region, then every other nationality in alphabetical order. */
const NEAR = ['Tanzanian', 'Kenyan', 'Ugandan', 'Rwandan', 'Burundian', 'Congolese (DRC)', 'Zambian', 'Malawian', 'Mozambican', 'Comorian', 'South Sudanese', 'Somali', 'Ethiopian'];
const OTHERS = [
  'Afghan', 'Albanian', 'Algerian', 'American', 'Andorran', 'Angolan', 'Antiguan and Barbudan', 'Argentine', 'Armenian', 'Australian', 'Austrian', 'Azerbaijani',
  'Bahamian', 'Bahraini', 'Bangladeshi', 'Barbadian', 'Belarusian', 'Belgian', 'Belizean', 'Beninese', 'Bhutanese', 'Bolivian', 'Bosnian and Herzegovinian',
  'Botswanan', 'Brazilian', 'British', 'Bruneian', 'Bulgarian', 'Burkinabe', 'Cambodian', 'Cameroonian', 'Canadian', 'Cape Verdean', 'Central African', 'Chadian',
  'Chilean', 'Chinese', 'Colombian', 'Congolese (Republic of the Congo)', 'Costa Rican', 'Croatian', 'Cuban', 'Cypriot', 'Czech', 'Danish', 'Djiboutian',
  'Dominican', 'Dutch', 'East Timorese', 'Ecuadorian', 'Egyptian', 'Emirati', 'Equatorial Guinean', 'Eritrean', 'Estonian', 'Eswatini (Swazi)', 'Fijian',
  'Filipino', 'Finnish', 'French', 'Gabonese', 'Gambian', 'Georgian', 'German', 'Ghanaian', 'Greek', 'Grenadian', 'Guatemalan', 'Guinea-Bissauan', 'Guinean',
  'Guyanese', 'Haitian', 'Honduran', 'Hungarian', 'Icelandic', 'Indian', 'Indonesian', 'Iranian', 'Iraqi', 'Irish', 'Israeli', 'Italian', 'Ivorian', 'Jamaican',
  'Japanese', 'Jordanian', 'Kazakh', 'Kiribati', 'Kuwaiti', 'Kyrgyz', 'Lao', 'Latvian', 'Lebanese', 'Liberian', 'Libyan', 'Liechtenstein', 'Lithuanian',
  'Luxembourgish', 'Malagasy', 'Malaysian', 'Maldivian', 'Malian', 'Maltese', 'Marshallese', 'Mauritanian', 'Mauritian', 'Mexican', 'Micronesian', 'Moldovan',
  'Monegasque', 'Mongolian', 'Montenegrin', 'Moroccan', 'Mosotho', 'Myanmar', 'Namibian', 'Nauruan', 'Nepalese', 'New Zealander', 'Nicaraguan', 'Nigerian',
  'Nigerien', 'North Korean', 'North Macedonian', 'Norwegian', 'Omani', 'Pakistani', 'Palauan', 'Palestinian', 'Panamanian', 'Papua New Guinean', 'Paraguayan',
  'Peruvian', 'Polish', 'Portuguese', 'Qatari', 'Romanian', 'Russian', 'Saint Lucian', 'Salvadoran', 'Samoan', 'San Marinese', 'Sao Tomean', 'Saudi',
  'Senegalese', 'Serbian', 'Seychellois', 'Sierra Leonean', 'Singaporean', 'Slovak', 'Slovenian', 'Solomon Islander', 'South African', 'South Korean', 'Spanish',
  'Sri Lankan', 'Sudanese', 'Surinamese', 'Swedish', 'Swiss', 'Syrian', 'Tajik', 'Thai', 'Togolese', 'Tongan', 'Trinidadian and Tobagonian', 'Tunisian',
  'Turkish', 'Turkmen', 'Tuvaluan', 'Ukrainian', 'Uruguayan', 'Uzbek', 'Vanuatuan', 'Venezuelan', 'Vietnamese', 'Yemeni', 'Zimbabwean',
];
export const NATIONALITIES = [...NEAR, ...OTHERS];

/** Nationality chosen from the list (typing filters it); a value saved earlier that is not on the list is kept and shown. */
export function NationalityField({ value, onChange, label, required }: { value?: string | null; onChange: (v: string) => void; label: string; required?: boolean }) {
  const v = value ?? '';
  const match = NATIONALITIES.find((n) => n.toLowerCase() === v.trim().toLowerCase());
  const options = match || !v ? NATIONALITIES : [v, ...NATIONALITIES];
  return (
    <Autocomplete fullWidth options={options} value={match ?? (v || null)} onChange={(_, n) => onChange(n ?? '')} autoHighlight
      renderInput={(p) => <TextField {...p} label={label} required={required} />} />
  );
}
