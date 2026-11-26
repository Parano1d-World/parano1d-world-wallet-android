'use strict';
// Character avatars, ported from iOS (CharacterAvatar.swift, HairArt.swift, PixelCharacterAvatar.swift, 198 / 1.0.0 (22)).
// Same 200×200 vector coordinates and 48-cell pixel grid, same drawing order and the same recipe format,
// so an avatar made or shared on iOS looks the same here, and the reverse. Rendered locally as SVG; no downloads.
window.CharacterAvatar = (() => {
  const O = Object.freeze({
    styles: ['Classic', 'Anonymous', 'Hooded', 'Cyber', 'Minimal'],
    presentations: ['Masculine', 'Feminine'],
    faces: ['Oval', 'Round', 'Angular', 'Alien', 'Cat', 'Robot'],
    nonHumanFaces: ['Alien', 'Cat', 'Robot'],
    skins: ['Porcelain', 'Fair', 'Warm', 'Tan', 'Brown', 'Deep', 'Mint', 'Lunar', 'Violet', 'Emerald'],
    expressions: ['Calm', 'Friendly', 'Focused', 'Wide', 'Wink', 'Sly', 'Glow'],
    hairstyles: ['Crop', 'Buzz', 'Undercut', 'Curls', 'Afro', 'Long', 'Bob', 'Ponytail', 'Bun', 'Braids', 'Mohawk', 'Spikes', 'Bald'],
    hairColors: ['Black', 'Brown', 'Blonde', 'Auburn', 'Copper', 'Silver', 'White', 'Pink', 'Violet', 'Blue', 'Mint', 'Neon'],
    facialHair: ['None', 'Stubble', 'Mustache', 'Goatee', 'Beard'],
    clothing: ['Jacket', 'Hoodie', 'T-shirt', 'Bomber', 'Turtleneck', 'Trench', 'Tactical', 'Lab Coat', 'Armor', 'Hero Armor', 'Suit', 'Spacesuit', 'Full Anonymous', 'Duster'],
    accessories: ['None', 'Glasses', 'Sunglasses', 'Shades', 'Visor', 'Cyber Eye', 'Monocle', 'Eyepatch', 'Mask', 'Bandana', 'Respirator', 'Piercing', 'Earpiece', 'Beanie', 'Cap', 'Headphones', 'Helmet', 'Cowboy Hat', 'Crown', 'Halo'],
    jewelry: ['None', 'Chain', 'Double chain', 'Pendant'],
    accents: ['Green', 'Mint', 'Lime', 'Cyan', 'Blue', 'Purple', 'Magenta', 'Pink', 'Red', 'Orange', 'Gold', 'Silver'],
    backgrounds: ['Midnight', 'Void', 'Ocean', 'Plum', 'Paper', 'Grid', 'Matrix', 'Exchange', 'Blockchain', 'Circuit', 'Vault', 'Skyline', 'Aurora', 'Sunset', 'Starlight', 'Rose'],
    pixelThemes: ['Neon', 'Arcade', 'Circuit', 'Shadow'],
    legacyStyles: ['Pixel'], legacyPresentations: ['Androgynous'], legacyFaces: ['Ape'],
    presets: ['Astronaut', 'Anonymous', 'Cowboy', 'Alien', 'Paranoid Cat', 'Cyber Pilot', 'Space Ranger', 'Crypto Trader', 'Cyber Hero', 'Hacker', 'Node Runner', 'Miner', 'Robot', 'Ghost', 'Vault Keeper', 'Neon Ronin', 'Whale'],
  });
  const SKIN = { Porcelain: 0xF3D8C5, Fair: 0xEBC3A2, Warm: 0xDCA577, Tan: 0xB97E54, Brown: 0x885636, Deep: 0x503426, Mint: 0x8ED8B9, Lunar: 0xAFC6C7, Violet: 0xA98CC6, Emerald: 0x67BE9C };
  const HAIR_COLOR = { Black: 0x171922, Brown: 0x51372D, Blonde: 0xDAB777, Auburn: 0x954930, Silver: 0xCDD6DE, Pink: 0xE571A1, Blue: 0x4C83CE, Mint: 0x69E4BA, Copper: 0xC4642D, Violet: 0x8E5CD9, White: 0xEEF1F2, Neon: 0x3BF07A };
  const BACKGROUND = { Paper: 0xDBE5DF, Plum: 0x30233F, Ocean: 0x153948, Grid: 0x102C2A, Exchange: 0x18232B, Circuit: 0x102D37, Sunset: 0x674337, Starlight: 0x253956, Rose: 0x4D2E4A, Matrix: 0x04140C, Vault: 0x1A2026, Aurora: 0x0D1F2A, Skyline: 0x121A33, Blockchain: 0x0C1A1F, Void: 0x030507 };
  const OUTFIT = { Armor: 0x344850, 'Hero Armor': 0x344850, Spacesuit: 0xDCE4E6, Duster: 0x69452E, Suit: 0x171E2C, Trench: 0x3B3428, Turtleneck: 0x1C1F26, Bomber: 0x2F3B2A, Tactical: 0x2C3328, 'Lab Coat': 0xE8EEF0 };
  // PersonalAvatar.tint (Green is the app's mint).
  const TINT = { Cyan: 0x32ADE6, Purple: 0xAF52DE, Orange: 0xFF9500, Pink: 0xFF2D55, Gold: 0xE4B94C, Red: 0xF06C73, Blue: 0x76A7F4, Silver: 0xC7D2DD, Mint: 0x5CF2C4, Lime: 0xB6F24A, Magenta: 0xE25CF2 };
  const tint = (name) => TINT[name] ?? 0x30E374;
  const HAIR = {"Buzz":{"back":[],"front":[["M62.5 89 C61 66 74 43.5 100 42.5 C126 43.5 139 66 137.5 89 C136.9 91.5 134.1 91.5 133.5 89 C133 80 131 72 127.5 67.5 C121 61.5 111 58.5 100 58.5 C89 58.5 79 61.5 72.5 67.5 C69 72 67 80 66.5 89 C65.9 91.5 63.1 91.5 62.5 89 Z","hair"],["M78 52 C86 46.5 94 45 100 45 C92 46.5 85 49 80 54 Z","light"]]},"Crop":{"back":[],"front":[["M61 84 C57 62 64 40 88 33.5 C110 28 134 34 140 56 C142 66 140 76 139 84 C138.3 86.5 135.2 86.5 134.5 84 C134 75 132 69 128.5 65 C124 62 118 61.5 112 63 C114 59 113 56 111 54 C107 58 101 59.5 95 59 C97 57 97 55 96 53.5 C90 58 82 61 74 63.5 C70 68 67.5 76 66 84 C65.2 86.5 61.8 86.5 61 84 Z","hair"],["M80 42 C88 37 98 35 106 35.5 C97 37.5 89 40.5 83 45 Z","light"]]},"Undercut":{"back":[],"front":[["M63.6 89 C62.8 66.5 75.8 45.6 100 45.5 C124.2 45.6 137.2 66.5 136.4 89 C136.0 91.5 133.9 91.5 133.5 89 C133 80 131.5 72 129 66 C120 61 110 59.5 100 59.5 C90 59.5 80 61 71 66 C68.5 72 67 80 66.5 89 C66.1 91.5 64.0 91.5 63.6 89 Z","stubble"],["M70.5 62 C66 47 78 33 100 32 C121 31 136 41 133 57 C132 62 129 66 126 69 C123 62 117 58.5 109 58.5 C99 58.5 89 62 80.5 64.5 C76 65.5 73 64.5 70.5 62 C70.5 64.5 70.5 64.5 70.5 62 Z","hair"],["M80.5 64.5 C89 62 99 58.5 109 58.5 C117 58.5 123 62 126 69 C121 63.5 114 61.5 107 61.8 C97 62.3 88 64 80.5 64.5 C80.5 67.0 80.5 67.0 80.5 64.5 Z","shade"],["M79 44 C87 38 96 35.5 105 35.7 C97 38 89 41 83 46.5 Z","light"]]},"Mohawk":{"back":[],"front":[["M63.2 89 C62.4 66.5 75.5 45.2 100 45 C124.5 45.2 137.6 66.5 136.8 89 C136.3 91.5 134.0 91.5 133.5 89 C133 80 131 72 127.5 67.5 C121 61.5 111 58.5 100 58.5 C89 58.5 79 61.5 72.5 67.5 C69 72 67 80 66.5 89 C66.0 91.5 63.7 91.5 63.2 89 Z","stubble"],["M91 60 C89 46 90 30 96 20 C99 15 104 13 108 16 C106 20 107 26 110 32 C112 40 111 50 109 60 C103 58.5 97 58.5 91 60 C91.0 62.5 91.0 62.5 91 60 Z","hair"],["M96 24 C98 19 101 17 104 17 C101 20 99 25 98 31 Z","light"]]},"Spikes":{"back":[],"front":[["M62.5 86 C61 70 63 60 69 52 C66 46 63 41 61 36 C67 38 72 41 76 44 C76 37 77 31 79 26 C83 31 86 36 88 41 C90 34 93 27 98 21 C100 28 101 34 102 40 C105 33 109 27 115 23 C115 30 114 36 113 42 C118 38 123 35 129 34 C127 40 125 45 122 49 C127 47 132 46 138 47 C135 52 132 56 130 59 C134 66 137.5 74 137.5 86 C136.5 88.5 134.5 88.5 133.5 86 C133 78 131 71 127.5 67 C121 61.5 111 59.5 100 59.5 C89 59.5 79 61.5 72.5 67 C69 71 67 78 66.5 86 C65.5 88.5 63.5 88.5 62.5 86 C62.5 88.5 62.5 88.5 62.5 86 Z","hair"],["M80 46 C84 42 88 41 91 42 M100 40 C103 36 107 34 110 34","lightLine"]]},"Afro":{"back":[["M100 18 C140 18 160 42 160 72 C160 98 148 112 138 118 C126.6 120.5 73.4 120.5 62 118 C52 112 40 98 40 72 C40 42 60 18 100 18 C100.0 20.5 100.0 20.5 100 18 Z","hair"]],"front":[["M64 84 C60 62 72 47 86 44 C96 42 106 42 116 44 C130 47 140 62 136 84 C134 72 128 64 118 60 C108 57 92 57 82 60 C72 64 66 72 64 84 C64.0 86.5 64.0 86.5 64 84 Z","fill"],["M60 40 C70 28 84 22 100 21 C88 25 76 31 66 42 Z","light"]]},"Curls":{"back":[],"front":[["M62 82 C56 74 58 62 64 58 C60 50 66 42 74 42 C76 34 86 30 93 34 C98 28 108 28 112 34 C120 30 130 36 128 43 C136 44 141 52 137 59 C142 64 143 74 138 82 C137.4 84.5 134.6 84.5 134 82 C134 74 131 69 127 66 C121 62 111 60 100 60 C89 60 79 62 73 66 C69 69 66 74 66 82 C65.4 84.5 62.6 84.5 62 82 Z","hair"],["M70 50 C72 46 76 45 79 46 M88 39 C90 36 94 35 97 37 M106 37 C109 35 113 36 115 39 M123 47 C126 46 129 48 130 51","lightLine"]]},"Long":{"back":[["M100 34 C130 34 148 54 149 84 C150 110 151 132 156 156 C150 162 132 164 100 164 C68 164 50 162 44 156 C49 132 50 110 51 84 C52 54 70 34 100 34 Z","hair"],["M100 34 C130 34 148 54 149 84 C150 110 151 132 156 156 C150 162 132 164 100 164 C68 164 50 162 44 156 C49 132 50 110 51 84 C52 54 70 34 100 34 Z","shade"]],"front":[["M100 37 C127 37 144 56 145 84 C146 104 145 124 148 142 C150 152 147 160 140 165 C137 153 130 143 124 134 C129 124 133 112 135 100 C136 88 134 76 129 68 C121 60 108 57 97 58.5 C88 59.5 78 64 72 72 C67 80 65 90 65 100 C67 112 71 124 76 134 C70 143 63 153 60 165 C53 160 50 152 52 142 C55 124 54 104 55 84 C56 56 73 37 100 37 Z","hair"],["M97 58.5 C88 59.5 78 64 72 72 C76 64 84 57.5 93 55 C95 55.5 96 57 97 58.5 Z","shade"],["M78 46 C86 41 94 39.2 101 39.2 C93 41.5 86 44.5 81 49 Z","light"],["M59 96 C58 116 59 134 61 150 M141 96 C142 116 141 134 139 150","lightLine"],["M66 112 C66 124 70 134 74 140 M134 112 C134 124 130 134 126 140","shadeLine"]]},"Bob":{"back":[["M100 36 C128 36 146 56 147 86 C148 104 147 120 144 132 C120 136 80 136 56 132 C53 120 52 104 53 86 C54 56 72 36 100 36 Z","hair"]],"front":[["M100 38 C136 40 150 70 147 100 C146 112 145 122 142 130 C136 132.5 131 131.5 128 128 C131 118 133 108 133 98 C133 86 132 78 130 72 C123 63 110 58.5 96 59.5 C86 60.5 77 65 71 73 C68 80 67 88 67 98 C67 108 69 118 72 128 C69 131.5 64 132.5 58 130 C55 122 54 112 53 100 C50 70 64 40 100 38 Z","hair"],["M96 59.5 C86 60.5 77 65 71 73 C74 66 82 59 92 56 C94 56.5 95 58 96 59.5 Z","shade"],["M78 47 C86 42 94 40.2 101 40.2 C93 42.5 86 45.5 81 50 Z","light"],["M58 102 C58 112 59 120 61 127 M142 102 C142 112 141 120 139 127","lightLine"]]},"Ponytail":{"back":[["M124 52 C138 50 148 60 150 78 C152 98 148 122 140 142 C137 146 133 145 132 141 C138 122 141 100 138 82 C136 70 131 62 124 60 Z","hair"]],"front":[["M61.5 90 C59 66 72 40 100 38 C128 40 141 66 138.5 90 C137.5 94 136 96 134.5 97 C134 84 131.5 72 127 66 C119 60 108 57.5 98 58.5 C90 59.5 82 62 76 66 C70.5 72 66 84 65.5 97 C64 96 62.5 94 61.5 90 Z","hair"],["M76 66 C82 62 90 59.5 98 58.5 C91 57 83 59.5 77 63 Z","shade"],["M79 47 C87 42 95 40.5 102 40.5 C94 42.5 87 45.5 82 50 Z","light"]]},"Bun":{"back":[],"front":[["M86 37 C83 27 90 19 100 19 C110 19 117 27 114 37 C109 41 91 41 86 37 C86.0 39.5 86.0 39.5 86 37 Z","hair"],["M92 24 C95 21 99 20.5 102 21.5 C98 22.5 95 24 94 26 Z","light"],["M61.5 90 C59 66 72 40 100 38 C128 40 141 66 138.5 90 C137.5 94 136 96 134.5 97 C134 84 131.5 72 127 66 C119 60 108 57.5 98 58.5 C90 59.5 82 62 76 66 C70.5 72 66 84 65.5 97 C64 96 62.5 94 61.5 90 Z","hair"],["M76 66 C82 62 90 59.5 98 58.5 C91 57 83 59.5 77 63 Z","shade"],["M79 47 C87 42 95 40.5 102 40.5 C94 42.5 87 45.5 82 50 Z","light"]]},"Braids":{"back":[["M100 40 C126 40 142 58 143 84 C144 98 140 110 134 116 L66 116 C60 110 56 98 57 84 C58 58 74 40 100 40 Z","hair"],["M52.9 96.4 C64.7 96.8 62.1 107.2 58.1 108.0 C51.5 107.2 46.3 96.8 52.9 96.4 Z","hair"],["M50.9 98.7 C53.5 102.0 57.5 103.0 60.1 106.3","shadeLine"],["M59.1 106.4 C60.3 106.8 62.9 117.2 53.9 118.0 C47.5 117.2 52.7 106.8 59.1 106.4 Z","hair"],["M52.0 111.3 C54.5 112.0 58.5 113.0 61.0 113.7","shadeLine"],["M56.4 116.4 C67.8 116.8 65.2 127.2 61.6 128.0 C55.4 127.2 50.2 116.8 56.4 116.4 Z","hair"],["M54.7 118.7 C57.0 122.0 61.0 123.0 63.3 126.3","shadeLine"],["M141.9 96.4 C153.7 96.8 151.1 107.2 147.1 108.0 C140.5 107.2 135.3 96.8 141.9 96.4 Z","hair"],["M139.9 98.7 C142.5 102.0 146.5 103.0 149.1 106.3","shadeLine"],["M146.1 106.4 C147.3 106.8 149.9 117.2 140.9 118.0 C134.5 117.2 139.7 106.8 146.1 106.4 Z","hair"],["M139.0 111.3 C141.5 112.0 145.5 113.0 148.0 113.7","shadeLine"],["M138.4 116.4 C149.8 116.8 147.2 127.2 143.6 128.0 C137.4 127.2 132.2 116.8 138.4 116.4 Z","hair"],["M136.7 118.7 C139.0 122.0 143.0 123.0 145.3 126.3","shadeLine"]],"front":[["M61.5 90 C59 66 72 40 100 38 C128 40 141 66 138.5 90 C137.5 94 136 96 134.5 97 C134 84 131.5 72 127 66 C119 60 108 57.5 98 58.5 C90 59.5 82 62 76 66 C70.5 72 66 84 65.5 97 C64 96 62.5 94 61.5 90 Z","hair"],["M76 66 C82 62 90 59.5 98 58.5 C91 57 83 59.5 77 63 Z","shade"],["M79 47 C87 42 95 40.5 102 40.5 C94 42.5 87 45.5 82 50 Z","light"],["M64.6 126.4 C65.3 126.8 67.9 137.2 59.4 138.0 C53.5 137.2 58.7 126.8 64.6 126.4 Z","hair"],["M57.8 131.3 C60.0 132.0 64.0 133.0 66.2 133.7","shadeLine"],["M61.9 136.4 C72.8 136.8 70.2 147.2 67.1 148.0 C61.4 147.2 56.2 136.8 61.9 136.4 Z","hair"],["M60.5 138.7 C62.5 142.0 66.5 143.0 68.5 146.3","shadeLine"],["M68.6 146.4 C68.9 146.8 71.5 157.2 63.4 158.0 C57.9 157.2 63.1 146.8 68.6 146.4 Z","hair"],["M62.1 151.3 C64.0 152.0 68.0 153.0 69.8 153.7","shadeLine"],["M64.4 156.4 C74.9 156.8 72.3 167.2 69.6 168.0 C64.3 167.2 59.1 156.8 64.4 156.4 Z","hair"],["M63.3 158.7 C65.0 162.0 69.0 163.0 70.7 166.3","shadeLine"],["M70.1 165.4 C70.0 165.8 72.6 176.2 64.9 177.0 C59.8 176.2 65.0 165.8 70.1 165.4 Z","hair"],["M64.0 170.3 C65.5 171.0 69.5 172.0 71.0 172.7","shadeLine"],["M63.5 175 C61.5 182 64.5 186 67.5 188 C70.5 186 73.5 182 71.5 175 Z","hair"],["M62.5 175.5 L72.5 175.5","shadeLine"],["M140.6 126.4 C141.3 126.8 143.9 137.2 135.4 138.0 C129.5 137.2 134.7 126.8 140.6 126.4 Z","hair"],["M133.8 131.3 C136.0 132.0 140.0 133.0 142.2 133.7","shadeLine"],["M132.9 136.4 C143.8 136.8 141.2 147.2 138.1 148.0 C132.4 147.2 127.2 136.8 132.9 136.4 Z","hair"],["M131.5 138.7 C133.5 142.0 137.5 143.0 139.5 146.3","shadeLine"],["M136.6 146.4 C136.9 146.8 139.5 157.2 131.4 158.0 C125.9 157.2 131.1 146.8 136.6 146.4 Z","hair"],["M130.2 151.3 C132.0 152.0 136.0 153.0 137.8 153.7","shadeLine"],["M130.4 156.4 C140.9 156.8 138.3 167.2 135.6 168.0 C130.3 167.2 125.1 156.8 130.4 156.4 Z","hair"],["M129.3 158.7 C131.0 162.0 135.0 163.0 136.7 166.3","shadeLine"],["M135.1 165.4 C135.0 165.8 137.6 176.2 129.9 177.0 C124.8 176.2 130.0 165.8 135.1 165.4 Z","hair"],["M129.0 170.3 C130.5 171.0 134.5 172.0 136.0 172.7","shadeLine"],["M128.5 175 C126.5 182 129.5 186 132.5 188 C135.5 186 138.5 182 136.5 175 Z","hair"],["M127.5 175.5 L137.5 175.5","shadeLine"]]}};

  const DEFAULT = Object.freeze({ style: 'Classic', presentation: 'Masculine', face: 'Oval', skin: 'Warm', hair: 'Crop', hairColor: 'Black', facialHair: 'None', eyes: 'Calm', accessory: 'None', clothing: 'Jacket', accent: 'Green', background: 'Midnight', branding: true });
  const isNonHuman = (c) => O.nonHumanFaces.includes(c.face);
  function removeHumanLayers(c) { c.hair = 'Bald'; c.facialHair = 'None'; if (c.style === 'Anonymous') c.style = 'Classic'; if (c.accessory === 'Mask') c.accessory = 'None'; return c; }
  /** Recipe from JSON text or an object; anything unreadable gives the default (like iOS decode). */
  function decode(raw) {
    let value = raw;
    try { if (typeof raw === 'string') { if (raw.length >= 4096) return { ...DEFAULT }; value = JSON.parse(raw); } } catch { return { ...DEFAULT }; }
    if (!value || typeof value !== 'object') return { ...DEFAULT };
    const c = { ...DEFAULT };
    for (const key of Object.keys(DEFAULT)) if (key in value && typeof value[key] === typeof DEFAULT[key]) c[key] = value[key];
    for (const key of ['rendering', 'pixelTheme', 'jewelry']) if (typeof value[key] === 'string') c[key] = value[key];
    if (c.presentation === 'Androgynous') c.presentation = 'Masculine';
    if (c.face === 'Ape') c.face = 'Oval';
    if (isNonHuman(c)) removeHumanLayers(c);
    return c;
  }
  /** iOS JSONEncoder output: every non-optional key present (branding is kept so older recipes still load). */
  function encode(c) {
    const out = {}; for (const key of Object.keys(DEFAULT)) out[key] = c[key] ?? DEFAULT[key];
    for (const key of ['rendering', 'pixelTheme', 'jewelry']) if (c[key] != null) out[key] = c[key];
    return out;
  }
  const isPixel = (c) => c.rendering === 'Pixel' || (c.rendering == null && c.style === 'Pixel');
  function valid(c) {
    const has = (list, v) => list.includes(v);
    return !!c && (has(O.styles, c.style) || has(O.legacyStyles, c.style)) && (c.rendering == null || ['Vector', 'Pixel'].includes(c.rendering))
      && (c.pixelTheme == null || has(O.pixelThemes, c.pixelTheme)) && (has(O.presentations, c.presentation) || has(O.legacyPresentations, c.presentation))
      && (has(O.faces, c.face) || has(O.legacyFaces, c.face)) && SKIN[c.skin] != null && HAIR_COLOR[c.hairColor] != null && has(O.hairstyles, c.hair)
      && has(O.facialHair, c.facialHair) && has(O.expressions, c.eyes) && has(O.accessories, c.accessory)
      && (c.jewelry == null || (c.jewelry !== 'None' && has(O.jewelry, c.jewelry))) && has(O.clothing, c.clothing) && has(O.accents, c.accent) && has(O.backgrounds, c.background);
  }
  function preset(name, current = DEFAULT) {
    // A look is a complete recipe; never carry another look's face, mask or outfit into it.
    const r = { ...DEFAULT };
    const rendering = current.rendering ?? (current.style === 'Pixel' ? 'Pixel' : undefined);
    if (rendering) r.rendering = rendering;
    if (current.pixelTheme) r.pixelTheme = current.pixelTheme;
    r.branding = current.branding ?? true;
    const set = (o) => Object.assign(r, o);
    switch (name) {
      case 'Astronaut': set({ clothing: 'Spacesuit', accessory: 'Helmet', background: 'Ocean', accent: 'Cyan' }); break;
      case 'Anonymous': set({ style: 'Anonymous', clothing: 'Full Anonymous', accessory: 'Mask', background: 'Midnight' }); break;
      case 'Cowboy': set({ clothing: 'Duster', accessory: 'Cowboy Hat', background: 'Paper', accent: 'Orange' }); break;
      case 'Alien': set({ face: 'Alien', hair: 'Bald', clothing: 'Spacesuit', accessory: 'None', skin: 'Mint', background: 'Plum' }); break;
      case 'Paranoid Cat': set({ face: 'Cat', hair: 'Bald', clothing: 'Jacket', accessory: 'None', background: 'Midnight' }); break;
      case 'Cyber Pilot': set({ style: 'Cyber', clothing: 'Armor', accessory: 'Visor', background: 'Grid', accent: 'Cyan' }); break;
      case 'Space Ranger': set({ clothing: 'Spacesuit', accessory: 'Helmet', background: 'Plum', accent: 'Purple' }); break;
      case 'Crypto Trader': set({ style: 'Cyber', clothing: 'Suit', accessory: 'Shades', jewelry: 'Chain', background: 'Exchange', accent: 'Gold' }); break;
      case 'Cyber Hero': set({ style: 'Cyber', clothing: 'Hero Armor', accessory: 'Visor', background: 'Circuit', accent: 'Cyan' }); break;
      case 'Hacker': set({ style: 'Hooded', clothing: 'Hoodie', hair: 'Undercut', eyes: 'Glow', accessory: 'Earpiece', background: 'Matrix' }); break;
      case 'Node Runner': set({ style: 'Cyber', presentation: 'Feminine', clothing: 'Bomber', hair: 'Ponytail', hairColor: 'Neon', accessory: 'Cyber Eye', background: 'Blockchain', accent: 'Mint' }); break;
      case 'Miner': set({ clothing: 'Tactical', accessory: 'Respirator', hair: 'Buzz', skin: 'Tan', background: 'Vault', accent: 'Orange' }); break;
      case 'Robot': set({ face: 'Robot', hair: 'Bald', clothing: 'Armor', eyes: 'Glow', background: 'Circuit', accent: 'Cyan' }); break;
      case 'Ghost': set({ style: 'Hooded', presentation: 'Feminine', clothing: 'Trench', accessory: 'Bandana', eyes: 'Sly', hair: 'Bob', hairColor: 'White', skin: 'Porcelain', background: 'Void', accent: 'Silver' }); break;
      case 'Vault Keeper': set({ presentation: 'Feminine', clothing: 'Turtleneck', accessory: 'Monocle', hair: 'Bun', hairColor: 'Silver', skin: 'Brown', background: 'Vault', accent: 'Gold' }); break;
      case 'Neon Ronin': set({ style: 'Cyber', clothing: 'Trench', hair: 'Bun', accessory: 'Bandana', facialHair: 'Goatee', skin: 'Fair', background: 'Skyline', accent: 'Magenta' }); break;
      case 'Whale': set({ clothing: 'Suit', accessory: 'Crown', jewelry: 'Double chain', eyes: 'Wink', hair: 'Afro', skin: 'Deep', background: 'Aurora', accent: 'Gold' }); break;
      default: return { ...current };
    }
    return r;
  }

  // ---- SVG helpers ----
  const hex = (v) => '#' + (v >>> 0).toString(16).padStart(6, '0').slice(-6);
  const col = (v, a = 1) => [v, a];
  const op = (c, a) => [c[0], c[1] * a];
  const WHITE = 0xFFFFFF, BLACK = 0x000000;
  const n = (v) => Math.round(v * 100) / 100;
  const paint = (c) => `fill="${hex(c[0])}"${c[1] < 1 ? ` fill-opacity="${n(c[1])}"` : ''}`;
  const strokeAttr = (c, w) => `fill="none" stroke="${hex(c[0])}"${c[1] < 1 ? ` stroke-opacity="${n(c[1])}"` : ''} stroke-width="${n(w)}" stroke-linecap="round" stroke-linejoin="round"`;
  let uid = 0;

  function vector(raw) {
    const c = decode(typeof raw === 'string' ? raw : JSON.stringify(raw));
    const id = `av${(++uid).toString(36)}`;
    const out = [], defs = [];
    const accent = col(tint(c.accent));
    const skin = col(SKIN[c.skin] ?? 0xDCA577);
    const hairHex = HAIR_COLOR[c.hairColor] ?? 0x171922;
    const hair = col(hairHex);
    const shade = (amount) => { const m = (s) => { const a = (hairHex >> s) & 255, b = (0x18212B >> s) & 255; return Math.round(a + (b - a) * amount); }; return col((m(16) << 16) | (m(8) << 8) | m(0)); };
    const brows = shade(0.5), facial = shade(0.25);
    const ink = col(0x18212B), gold = col(0xE4B94C);
    const special = isNonHuman(c);
    const faceWidth = c.face === 'Round' ? 83 : c.face === 'Angular' ? 71 : 75;
    const p = (x, y) => [x, y];
    const add = (s) => out.push(s);
    const ellipse = (x, y, w, h, k) => add(`<ellipse cx="${n(x + w / 2)}" cy="${n(y + h / 2)}" rx="${n(w / 2)}" ry="${n(h / 2)}" ${paint(k)}/>`);
    const ring = (x, y, w, h, k, width) => add(`<ellipse cx="${n(x + w / 2)}" cy="${n(y + h / 2)}" rx="${n(w / 2)}" ry="${n(h / 2)}" ${strokeAttr(k, width)}/>`);
    const rect = (x, y, w, h, k) => add(`<rect x="${n(x)}" y="${n(y)}" width="${n(w)}" height="${n(h)}" ${paint(k)}/>`);
    const roundedBox = (x, y, w, h, r, { fill, stroke, width = 0 } = {}) => {
      const base = `x="${n(x)}" y="${n(y)}" width="${n(w)}" height="${n(h)}" rx="${n(Math.min(r, w / 2, h / 2))}"`;
      if (fill) add(`<rect ${base} ${paint(fill)}/>`);
      if (stroke) add(`<rect ${base} ${strokeAttr(stroke, width)}/>`);
    };
    const pts = (points) => points.map(([x, y]) => `${n(x)},${n(y)}`).join(' ');
    const polygon = (points, k) => { if (points.length) add(`<polygon points="${pts(points)}" ${paint(k)}/>`); };
    const line = (points, k, width = 2) => { if (points.length) add(`<polyline points="${pts(points)}" ${strokeAttr(k, width)}/>`); };
    const mid = (a, b) => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];
    const smoothD = (points) => {
      const first = mid(points[points.length - 1], points[0]);
      let d = `M${n(first[0])} ${n(first[1])}`;
      points.forEach((q, i) => { const e = mid(q, points[(i + 1) % points.length]); d += ` Q${n(q[0])} ${n(q[1])} ${n(e[0])} ${n(e[1])}`; });
      return d + ' Z';
    };
    const smooth = (points, k, clip) => { if (points.length > 2) add(`<path d="${smoothD(points)}" ${paint(k)}${clip ? ` clip-path="url(#${clip})"` : ''}/>`); };
    const curve = (points, k, width) => {
      if (points.length <= 2) return line(points, k, width);
      let d = `M${n(points[0][0])} ${n(points[0][1])}`;
      for (let i = 1; i < points.length - 1; i++) {
        const end = i === points.length - 2 ? points[i + 1] : mid(points[i], points[i + 1]);
        d += ` Q${n(points[i][0])} ${n(points[i][1])} ${n(end[0])} ${n(end[1])}`;
      }
      add(`<path d="${d}" ${strokeAttr(k, width)}/>`);
    };
    const fillPath = (d, k, extra = '') => add(`<path d="${d}" ${paint(k)}${extra}/>`);
    const strokePath = (d, k, w) => add(`<path d="${d}" ${strokeAttr(k, w)}/>`);
    const f = (v) => v.toFixed(2);
    const fem = c.presentation === 'Feminine';

    // Hair is clipped under whatever covers the head: a hat, helmet visor, hood or spacesuit collar.
    const hood = c.style === 'Hooded' || c.style === 'Anonymous' || c.clothing === 'Hoodie' || c.clothing === 'Full Anonymous';
    let hairClip = null;
    const clip = (shape) => { const cid = `${id}c${defs.length}`; defs.push(`<clipPath id="${cid}">${shape}</clipPath>`); return cid; };
    if (c.clothing === 'Spacesuit') hairClip = clip('<ellipse cx="100" cy="92" rx="55" ry="60"/>');
    else if (hood) hairClip = clip('<ellipse cx="100" cy="95" rx="52" ry="63"/>');
    else if (c.accessory === 'Beanie' || c.accessory === 'Cap') hairClip = clip('<rect x="0" y="60" width="200" height="140"/>');
    else if (c.accessory === 'Cowboy Hat') hairClip = clip('<rect x="0" y="56" width="200" height="144"/>');
    else if (c.accessory === 'Crown') hairClip = clip('<rect x="0" y="44" width="200" height="156"/>');
    else if (c.accessory === 'Helmet') hairClip = clip('<rect x="54" y="47" width="92" height="91" rx="30"/>');
    function drawHairArt(layers) {
      if (!layers?.length) return;
      const sx = faceWidth / 75, sy = c.face === 'Round' ? 47 / 51 : 1;
      const fit = `translate(100 45) scale(${n(sx)} ${n(sy)}) translate(-100 -45)`;
      const parts = layers.map(([d, role]) => {
        switch (role) {
          case 'hair': return `<path d="${d}" ${paint(hair)} stroke="#000" stroke-opacity="0.16" stroke-width="0.9"/>`;
          case 'fill': return `<path d="${d}" ${paint(hair)}/>`;
          case 'stubble': return `<path d="${d}" ${paint(op(hair, 0.4))}/>`;
          case 'shade': return `<path d="${d}" ${paint(col(BLACK, 0.22))}/>`;
          case 'light': return `<path d="${d}" ${paint(col(WHITE, 0.16))}/>`;
          case 'lightLine': return `<path d="${d}" fill="none" stroke="#fff" stroke-opacity="0.16" stroke-width="1.6" stroke-linecap="round"/>`;
          case 'shadeLine': return `<path d="${d}" fill="none" stroke="#000" stroke-opacity="0.22" stroke-width="1.6" stroke-linecap="round"/>`;
          default: return '';
        }
      }).join('');
      add(`<g${hairClip ? ` clip-path="url(#${hairClip})"` : ''}><g transform="${fit}">${parts}</g></g>`);
    }

    // Background
    rect(0, 0, 200, 200, col(BACKGROUND[c.background] ?? 0x0A151B));
    switch (c.background) {
      case 'Grid': for (let x = 0; x <= 200; x += 20) line([p(x, 0), p(x, 200)], op(accent, 0.16), 1); break;
      case 'Exchange':
        for (let x = 0; x <= 200; x += 25) line([p(x, 0), p(x, 200)], op(accent, 0.08), 1);
        line([p(0, 125), p(35, 108), p(70, 117), p(104, 75), p(143, 88), p(200, 42)], op(accent, 0.55), 2); break;
      case 'Circuit':
        for (let y = 8; y <= 200; y += 24) line([p(0, y), p(200, y)], op(accent, 0.12), 1);
        for (let x = 12; x <= 200; x += 24) line([p(x, 0), p(x, 200)], op(accent, 0.12), 1); break;
      case 'Starlight': for (let x = 18; x <= 185; x += 33) ellipse(x, (x * 31) % 170, 3, 3, col(WHITE, 0.7)); break;
      case 'Matrix':
        for (let column = 0; column < 13; column++) {
          const x = 6 + column * 16, top = (column * 37) % 60;
          for (let k = 0; k < 9; k++) if (top + k * 16 < 200) rect(x, top + k * 16, 3, 8, op(accent, 0.10 + 0.05 * ((column + k) % 3)));
        }
        break;
      case 'Vault':
        ring(10, 10, 180, 180, op(accent, 0.22), 3); ring(30, 30, 140, 140, op(accent, 0.14), 2);
        for (let k = 0; k < 12; k++) { const a = k * Math.PI / 6; line([p(100 + 70 * Math.cos(a), 100 + 70 * Math.sin(a)), p(100 + 90 * Math.cos(a), 100 + 90 * Math.sin(a))], op(accent, 0.20), 3); }
        break;
      case 'Aurora':
        polygon([p(0, 70), p(40, 40), p(90, 62), p(140, 30), p(200, 52), p(200, 88), p(140, 66), p(90, 96), p(40, 76), p(0, 104)], op(accent, 0.22));
        polygon([p(0, 104), p(50, 84), p(100, 104), p(150, 80), p(200, 96), p(200, 118), p(150, 102), p(100, 124), p(50, 106), p(0, 126)], col(0x8E5CD9, 0.18)); break;
      case 'Skyline': {
        const towers = [[0, 70], [22, 105], [44, 80], [70, 120], [98, 90], [124, 130], [150, 85], [174, 110]];
        towers.forEach(([tx, th], index) => {
          rect(tx, 200 - th, 24, th, col(0x0A1022));
          for (let wy = Math.trunc(200 - th) + 8; wy < 196; wy += 12) {
            rect(tx + 6, wy, 3, 4, op(accent, (index + wy) % 3 !== 0 ? 0.35 : 0.12));
            rect(tx + 14, wy, 3, 4, op(accent, (index + wy) % 2 !== 0 ? 0.12 : 0.30));
          }
        });
        break;
      }
      case 'Blockchain': {
        const blocks = [[14, 22], [60, 12], [112, 26], [158, 14]];
        blocks.forEach(([bx, by], index) => {
          roundedBox(bx, by, 30, 22, 4, { stroke: op(accent, 0.35), width: 2 });
          if (index < 3) line([p(bx + 30, by + 11), p(bx + 46, by + 11 + (index % 2 === 0 ? 4 : -4))], op(accent, 0.30), 2);
        });
        break;
      }
      case 'Void': ring(20, 20, 160, 160, op(accent, 0.10), 1); break;
      default: break;
    }
    if (c.style !== 'Minimal') ellipse(16, 10, 168, 168, op(accent, 0.14));
    if (c.style === 'Cyber') {
      for (let x = 12; x <= 188; x += 16) line([p(x, 0), p(x, 200)], op(accent, 0.12), 1);
      for (let y = 8; y <= 200; y += 16) line([p(0, y), p(200, y)], op(accent, 0.12), 1);
    }

    // Body
    const shoulders = c.presentation === 'Masculine' ? 154 : c.presentation === 'Feminine' ? 130 : 142;
    if (!special) drawHairArt(HAIR[c.hair]?.back);
    const outfit = c.clothing === 'T-shirt' ? op(accent, 0.8) : col(OUTFIT[c.clothing] ?? 0x26333F);
    ellipse((200 - shoulders) / 2, 141, shoulders, 112, outfit);
    ellipse((200 - shoulders) / 2 + 8, 176, shoulders - 16, 70, col(BLACK, 0.12));
    if (hood) { ellipse(39, 22, 122, 148, col(0x25323A)); ellipse(48, 32, 104, 126, col(0x101A21)); }
    if (c.clothing === 'Spacesuit') { ellipse(31, 18, 138, 151, col(0xB9C8CB)); ellipse(37, 24, 126, 137, op(accent, 0.5)); ellipse(45, 32, 110, 120, col(0x101A21)); }
    if (!special) {
      polygon([p(88, 120), p(112, 120), p(115, 154), p(85, 154)], skin);
      smooth([p(87, 122), p(113, 122), p(113, 134), p(100, 139), p(87, 134)], col(BLACK, 0.16));
    }
    const width = faceWidth, left = (200 - width) / 2, headRight = left + width;
    const skullCY = c.face === 'Round' ? 92 : c.face === 'Angular' ? 77 : 96;
    const skullRY = c.face === 'Round' ? 47 : c.face === 'Angular' ? 32 : 51;
    const skullRX = width / 2;
    const skullHalf = (y) => { if (y >= skullCY) return skullRX; const q = (skullCY - y) / skullRY; return skullRX * Math.sqrt(Math.max(0, 1 - q * q)); };
    function arcPoints(temple, padX, padY, taper = true) {
      const s0 = Math.min(1, Math.max(0, (skullCY - temple) / skullRY)), t0 = Math.asin(s0);
      let points = [];
      for (let i = 0; i <= 24; i++) {
        const t = t0 + (Math.PI - 2 * t0) * i / 24;
        const k = !taper ? 1 : s0 >= 1 ? 0 : Math.sqrt(Math.max(0, (Math.sin(t) - s0) / (1 - s0)));
        points.push(p(100 - (skullRX + padX * k) * Math.cos(t), skullCY - (skullRY + padY * k) * Math.sin(t)));
      }
      if (temple > skullCY) points = [p(100 - skullRX, temple), ...points, p(100 + skullRX, temple)];
      return points;
    }
    if (!special) { ellipse(left - 7, 84, 16, 25, skin); ellipse(left + width - 9, 84, 16, 25, skin); }

    // Head
    switch (c.face) {
      case 'Cat':
        polygon([p(59, 79), p(57, 28), p(79, 56), p(121, 56), p(143, 28), p(141, 79), p(133, 129), p(100, 144), p(67, 129)], skin);
        polygon([p(62, 39), p(68, 67), p(78, 57)], op(accent, 0.7));
        polygon([p(138, 39), p(132, 67), p(122, 57)], op(accent, 0.7)); break;
      case 'Alien':
        polygon([p(100, 27), p(129, 38), p(148, 65), p(145, 94), p(128, 121), p(111, 139), p(100, 149), p(89, 139), p(72, 121), p(55, 94), p(52, 65), p(71, 38)], skin);
        ellipse(61, 46, 78, 50, skin); break;
      case 'Robot': {
        const metal = col(0x9AA8B2), seam = col(0x6E7C86);
        rect(90, 128, 20, 26, col(0x5E6B74));
        for (const y of [133, 141, 149]) rect(90, y, 20, 2, col(BLACK, 0.25));
        roundedBox(58, 40, 84, 96, 18, { fill: metal, stroke: seam, width: 3 });
        rect(52, 76, 8, 26, seam); rect(140, 76, 8, 26, seam);
        line([p(100, 40), p(100, 24)], seam, 3); ellipse(95, 17, 10, 10, accent);
        roundedBox(68, 78, 64, 22, 8, { fill: ink });
        rect(80, 116, 40, 8, ink);
        for (let k = 0; k < 4; k++) rect(84 + k * 8, 116, 2, 8, metal);
        line([p(62, 50), p(70, 44)], col(WHITE, 0.5), 3); break;
      }
      case 'Angular':
        ellipse(left, 45, width, 64, skin);
        fillPath(angularJaw(left, width, fem), skin); break;
      default:
        fillPath(headOutline(left, width, c.face === 'Round' ? 47 : 51, fem), skin);
    }
    if (!special && fem) { ellipse(left + 7, 98, 15, 9, col(0xE0736B, 0.20)); ellipse(left + width - 22, 98, 15, 9, col(0xE0736B, 0.20)); }

    if (!special) drawHairArt(HAIR[c.hair]?.front);

    function eye(cx, cy, kind, side) {
      if (kind === 'Wink' && side > 0) { strokePath(`M${cx - 5.5} ${cy} C${cx - 2} ${cy + 3} ${cx + 2} ${cy + 3} ${cx + 5.5} ${cy}`, op(ink, 0.9), 2); return; }
      const rx = fem ? 5.6 : 5.0;
      const ry = kind === 'Wide' ? (fem ? 4.6 : 3.7) : (fem ? 3.9 : 2.9);
      const ir = fem ? 3.3 : 2.8;
      const glow = kind === 'Glow';
      if (glow) ellipse(cx - 8, cy - 7.5, 16, 15, op(accent, 0.35));
      ellipse(cx - rx, cy - ry, rx * 2, ry * 2, col(0xF5F0EA));
      ellipse(cx - ir, cy + 0.2 - ir, ir * 2, ir * 2, glow ? accent : col(0x3A2B22));
      ellipse(cx - ir / 2, cy + 0.2 - ir / 2, ir, ir, glow ? op(accent, 0.6) : col(0x0D1116));
      ellipse(cx + 1.1 - 0.95, cy - 1 - 0.95, 1.9, 1.9, col(WHITE));
      const top = cy - ry;
      const drop = kind === 'Focused' ? 1.6 : kind === 'Sly' ? 2.6 : 0;
      fillPath(`M${f(cx - rx - 1.5)} ${f(cy - ry - 3)} L${f(cx + rx + 1.5)} ${f(cy - ry - 3)} L${f(cx + rx + 1.5)} ${f(cy - ry + drop + 0.3)} C${f(cx + rx * 0.5)} ${f(top - 0.8 + drop)} ${f(cx - rx * 0.5)} ${f(top - 0.8 + drop)} ${f(cx - rx - 1.5)} ${f(cy - ry + drop + 0.3)} Z`, skin);
      if (kind === 'Friendly') fillPath(`M${f(cx - rx - 1.5)} ${f(cy + ry + 3)} L${f(cx + rx + 1.5)} ${f(cy + ry + 3)} L${f(cx + rx + 1.5)} ${f(cy + 1.2)} C${f(cx + rx * 0.5)} ${f(cy - 0.6)} ${f(cx - rx * 0.5)} ${f(cy - 0.6)} ${f(cx - rx - 1.5)} ${f(cy + 1.2)} Z`, skin);
      const lidArch = fem ? 1 : 0.2;
      strokePath(`M${f(cx - rx - 0.6)} ${f(cy + 0.4 + drop * 0.4)} C${f(cx - rx * 0.6)} ${f(top - lidArch + drop)} ${f(cx + rx * 0.6)} ${f(top - lidArch + drop)} ${f(cx + rx + 0.6)} ${f(cy + 0.4 + drop * 0.4)}`, op(ink, 0.92), fem ? 2.4 : 2.0);
      if (fem) {
        const ox = cx + side * (rx + 0.3);
        strokePath(`M${f(ox)} ${f(cy - 0.8)} L${f(ox + side * 3.0)} ${f(cy - 2.8)} M${f(ox - side * 1.8)} ${f(cy - 2.9)} L${f(ox + side * 0.4)} ${f(cy - 5.2)} M${f(ox - side * 4.0)} ${f(cy - 3.6)} L${f(ox - side * 3.2)} ${f(cy - 6.0)}`, op(ink, 0.92), 1.4);
      } else {
        strokePath(`M${f(cx - rx + 0.8)} ${f(cy + ry + 0.9)} C${f(cx - 1.5)} ${f(cy + ry + 1.9)} ${f(cx + 1.5)} ${f(cy + ry + 1.9)} ${f(cx + rx - 0.8)} ${f(cy + ry + 0.9)}`, col(BLACK, 0.12), 1.2);
      }
    }
    if (!special) {
      for (const side of [-1, 1]) {
        const cx = 100 + side * 15;
        const y = (fem ? 78.6 : 80.6) + (c.eyes === 'Focused' ? 0.6 : 0);
        const lift = c.eyes === 'Sly' && side > 0 ? -1.6 : 0;
        const arch = fem ? 2.8 : 0.5, tail = fem ? 1.8 : 0.6;
        strokePath(`M${f(cx - side * 6.8)} ${f(y + 1.0 + lift * 0.3)} Q${f(cx + side * 2.5)} ${f(y - arch + lift - 0.8)} ${f(cx + side * 7.4)} ${f(y + tail + lift)}`, brows, fem ? 2.0 : 3.6);
      }
      eye(85, 89, c.eyes, -1); eye(115, 89, c.eyes, 1);
    }
    switch (c.face) {
      case 'Alien':
        polygon([p(63, 82), p(82, 75), p(93, 88), p(83, 108), p(69, 102)], ink);
        polygon([p(137, 82), p(118, 75), p(107, 88), p(117, 108), p(131, 102)], ink);
        ellipse(76, 85, 5, 7, op(accent, 0.55)); ellipse(119, 85, 5, 7, op(accent, 0.55));
        line([p(94, 119), p(106, 119)], op(ink, 0.7), 2); break;
      case 'Robot':
        ellipse(76, 83, 14, 12, op(accent, 0.95)); ellipse(110, 83, 14, 12, op(accent, 0.95));
        ellipse(80, 86, 4, 4, col(WHITE, 0.85)); ellipse(114, 86, 4, 4, col(WHITE, 0.85)); break;
      case 'Cat':
        ellipse(76, 87, 10, 11, ink); ellipse(114, 87, 10, 11, ink);
        polygon([p(92, 105), p(108, 105), p(100, 114)], ink);
        line([p(100, 114), p(100, 121)], ink, 2);
        line([p(87, 117), p(100, 122), p(113, 117)], ink, 2);
        for (const y of [106, 113]) { line([p(62, y), p(86, y + 3)], op(ink, 0.6), 1); line([p(114, y + 3), p(138, y)], op(ink, 0.6), 1); }
        break;
      default: {
        strokePath(`M${f(left - 4)} 92 C${f(left - 4.5)} 89 ${f(left - 2)} 87.5 ${f(left)} 89 C${f(left + 1)} 93 ${f(left + 0.5)} 99 ${f(left - 1)} 103`, col(BLACK, 0.14), 1.3);
        strokePath(`M${f(left + width + 4)} 92 C${f(left + width + 4.5)} 89 ${f(left + width + 2)} 87.5 ${f(left + width)} 89 C${f(left + width - 1)} 93 ${f(left + width - 0.5)} 99 ${f(left + width + 1)} 103`, col(BLACK, 0.14), 1.3);
        strokePath('M101.5 95 C102.5 99.5 103.5 103 103.8 105.5', col(BLACK, 0.13), 1.3);
        strokePath('M95.8 106.2 C97 108.4 99 108.8 100 108.2 C101 108.8 103 108.4 104.2 106.2', op(ink, 0.35), 1.5);
        ellipse(96.4, 102.4, 7.2, 4.8, col(WHITE, 0.10));
        const right = left + width;
        const bottom = c.face === 'Round' ? 151 : c.face === 'Angular' ? 147 : 153;
        const mustache = [p(85, 117), p(92, 112.5), p(100, 114.5), p(108, 112.5), p(115, 117), p(110, 119.5), p(100, 117.5), p(90, 119.5)];
        switch (c.facialHair) {
          case 'Beard': case 'Stubble': {
            const beard = [p(left + 1, 84), p(left + 5, 103), p(75, 113), p(86, 110), p(100, 112), p(114, 110), p(125, 113), p(right - 5, 103), p(right - 1, 84),
              p(right + 1, 112), p(130, 136), p(116, bottom - 4), p(100, bottom), p(84, bottom - 4), p(70, 136), p(left - 1, 112)];
            if (c.facialHair === 'Beard') {
              smooth(beard, facial);
              smooth([p(70, 128), p(84, 142), p(100, bottom - 6), p(116, 142), p(130, 128), p(126, 142), p(100, bottom), p(74, 142)], col(BLACK, 0.18));
              smooth(mustache, facial);
            } else {
              const head = clip(`<path d="${c.face === 'Angular' ? angularJaw(left, width, fem) : headOutline(left, width, c.face === 'Round' ? 47 : 51, fem)}"/>`);
              smooth(beard, op(brows, 0.17), head);
              smooth(mustache, op(brows, 0.22));
            }
            break;
          }
          case 'Mustache': smooth(mustache, facial); break;
          case 'Goatee':
            smooth(mustache, facial);
            smooth([p(90, 127), p(100, 126), p(110, 127), p(109, 136), p(104, bottom - 7), p(96, bottom - 7), p(91, 136)], facial); break;
          default: break;
        }
        const covered = c.accessory === 'Bandana' || c.accessory === 'Respirator' || c.accessory === 'Mask';
        const mouthInk = op(ink, c.facialHair === 'Beard' ? 0.85 : 0.72);
        if (c.facialHair === 'Beard') fillPath('M90 120.5 C95 119 105 119 110 120.5 C106 125 94 125 90 120.5 Z', col(0x8C4F45, 0.9));
        if (c.eyes === 'Friendly') {
          fillPath('M90.5 119.5 C94 126.5 106 126.5 109.5 119.5 C103 121 97 121 90.5 119.5 Z', col(0x5A2328));
          fillPath('M92.5 120.4 C97 121.6 103 121.6 107.5 120.4 L107 122 C102.5 123 97.5 123 93 122 Z', col(WHITE, 0.92));
        } else {
          if (fem && c.facialHair !== 'Beard' && !covered) fillPath('M91 121 C94 119 97 118.6 100 119.6 C103 118.6 106 119 109 121 C106 125.6 94 125.6 91 121 Z', col(0xB4505A, 0.55));
          if (c.eyes === 'Sly') strokePath('M91 122 C95 123 101 122.6 104 121.4 C106 120.6 108 119.4 109.5 118.2', mouthInk, 1.9);
          else if (fem) strokePath('M91.5 121 C95.5 123.3 104.5 123.3 108.5 121', mouthInk, 1.9);
          else strokePath('M91.5 121.6 C95.5 122.8 104.5 122.8 108.5 121.6', mouthInk, 2.1);
        }
        if (c.facialHair !== 'Beard') strokePath('M96.5 126.8 C98.5 127.5 101.5 127.5 103.5 126.8', col(BLACK, 0.07), 1.1);
      }
    }

    // Face accessories
    if (c.style === 'Anonymous' || c.accessory === 'Mask') {
      // The mask is drawn for a 68-wide face; widen it to this face so no cheek or jaw shows past its edges.
      const maskScale = Math.max(1, (width + 6) / 68);
      const mq = (x, y) => p(100 + (x - 100) * maskScale, y);
      const porcelain = col(0xE6EBE5), contour = col(0xB7C6C1);
      polygon([mq(66, 69), mq(79, 61), mq(100, 58), mq(121, 61), mq(134, 69), mq(132, 113), mq(119, 136), mq(100, 148), mq(81, 136), mq(68, 113)], porcelain);
      polygon([mq(68, 75), mq(75, 70), mq(76, 119), mq(91, 140), mq(81, 133), mq(69, 109)], op(contour, 0.5));
      polygon([mq(132, 75), mq(125, 70), mq(124, 119), mq(109, 140), mq(119, 133), mq(131, 109)], op(contour, 0.5));
      line([mq(73, 85), mq(89, 82), mq(94, 87)], ink, 4);
      line([mq(106, 87), mq(111, 82), mq(127, 85)], ink, 4);
      polygon([mq(75, 91), mq(88, 89), mq(93, 94), mq(85, 98)], ink);
      polygon([mq(107, 94), mq(112, 89), mq(125, 91), mq(115, 98)], ink);
      line([mq(100, 93), mq(95, 110), mq(100, 113), mq(105, 110)], contour, 2);
      ellipse(100 + (75 - 100) * maskScale, 108, 13 * maskScale, 5, col(0xB97982, 0.5)); ellipse(100 + (112 - 100) * maskScale, 108, 13 * maskScale, 5, col(0xB97982, 0.5));
      polygon([mq(78, 119), mq(87, 118), mq(96, 116), mq(100, 119), mq(104, 116), mq(113, 118), mq(122, 119), mq(112, 123), mq(103, 122), mq(100, 125), mq(97, 122), mq(88, 123)], ink);
      line([mq(83, 118), mq(76, 115)], ink, 2); line([mq(117, 118), mq(124, 115)], ink, 2);
      line([mq(89, 130), mq(100, 133), mq(111, 130)], op(ink, 0.75), 2);
      polygon([mq(94, 136), mq(100, 139), mq(106, 136), mq(100, 147)], ink);
    } else {
      switch (c.accessory) {
        case 'Glasses': case 'Shades': case 'Sunglasses':
          for (const x of [68, 108]) roundedBox(x, 83, 26, 20, 6, { fill: c.accessory === 'Glasses' ? op(accent, 0.15) : ink, stroke: accent, width: 2 });
          line([p(94, 90), p(108, 90)], accent); break;
        case 'Visor': roundedBox(65, 82, 70, 22, 7, { fill: op(accent, 0.85) }); line([p(72, 88), p(124, 88)], col(WHITE, 0.7), 2); break;
        case 'Piercing': ellipse(57, 105, 6, 9, accent); break;
        case 'Monocle': ring(108, 81, 24, 24, gold, 2.5); ellipse(110, 83, 20, 20, col(WHITE, 0.12)); line([p(132, 96), p(138, 118), p(134, 150)], gold, 1.5); break;
        case 'Eyepatch': line([p(60, 74), p(140, 98)], ink, 3); ellipse(108, 80, 26, 22, ink); break;
        case 'Cyber Eye':
          roundedBox(106, 80, 28, 22, 6, { fill: col(0x2A3640), stroke: accent, width: 2 });
          ellipse(114, 85, 12, 12, accent); ellipse(118, 89, 4, 4, col(WHITE, 0.9));
          line([p(134, 91), p(146, 91), p(150, 104)], op(accent, 0.8), 2); break;
        case 'Bandana':
          polygon([p(64, 106), p(136, 106), p(132, 128), p(100, 146), p(68, 128)], op(accent, 0.9));
          for (let x = 74; x < 128; x += 10) ellipse(x, 116, 4, 4, col(WHITE, 0.35)); break;
        case 'Respirator':
          roundedBox(76, 108, 48, 30, 12, { fill: col(0x2C3338) });
          ellipse(64, 114, 20, 20, col(0x4A555D)); ellipse(116, 114, 20, 20, col(0x4A555D));
          ellipse(69, 119, 10, 10, op(accent, 0.7)); ellipse(121, 119, 10, 10, op(accent, 0.7));
          for (const y of [116, 122, 128]) line([p(88, y), p(112, y)], col(BLACK, 0.5), 1.5); break;
        default: break;
      }
    }

    // Headwear
    switch (c.accessory) {
      case 'Beanie': {
        const dome = arcPoints(68, 7, 18, false);
        polygon([...dome, p(headRight + 4, 68), p(left - 4, 68)], accent);
        const cuff = [p(left - 5, 58), p(100, 53), p(headRight + 5, 58), p(headRight + 5, 71), p(100, 67), p(left - 5, 71)];
        smooth(cuff, accent); smooth(cuff, col(BLACK, 0.14));
        for (let k = 1; k <= 11; k++) { const x = left - 3 + (width + 6) * k / 12; line([p(x, 58 - 4 * Math.cos((x - 100) / 50)), p(x, 68 - 2 * Math.cos((x - 100) / 50))], col(BLACK, 0.12), 1); }
        break;
      }
      case 'Cap': {
        const crown = arcPoints(66, 6, 20, false);
        polygon([...crown, p(headRight + 4, 66), p(left - 4, 66)], accent);
        polygon([...crown, p(headRight + 4, 66), p(left - 4, 66)], col(BLACK, 0.10));
        line([p(100, skullCY - skullRY - 20), p(100, 64)], col(BLACK, 0.18), 1.2); ellipse(97, skullCY - skullRY - 22, 6, 4, accent);
        smooth([p(left - 7, 63), p(100, 57), p(headRight + 7, 63), p(headRight + 6, 70), p(100, 75), p(left - 6, 70)], accent);
        smooth([p(left - 6, 68), p(100, 71), p(headRight + 6, 68), p(headRight + 6, 70), p(100, 75), p(left - 6, 70)], col(BLACK, 0.28));
        break;
      }
      case 'Cowboy Hat': ellipse(40, 40, 120, 24, col(0x69452E)); rect(70, 20, 60, 29, col(0x69452E)); rect(72, 41, 56, 5, accent); break;
      case 'Helmet': roundedBox(50, 43, 100, 99, 34, { stroke: col(0xDCE4E6), width: 9 }); line([p(60, 64), p(70, 49), p(90, 42)], col(WHITE, 0.65), 4); break;
      case 'Headphones': ellipse(48, 61, 12, 53, accent); ellipse(140, 61, 12, 53, accent); line([p(54, 68), p(60, 38), p(100, 28), p(140, 38), p(146, 68)], accent, 4); break;
      case 'Crown':
        polygon([p(66, 44), p(66, 18), p(80, 32), p(90, 12), p(100, 28), p(110, 12), p(120, 32), p(134, 18), p(134, 44)], gold);
        rect(66, 38, 68, 8, col(0xC99B2E));
        ellipse(78, 37, 7, 7, accent); ellipse(97, 37, 7, 7, col(0xF06C73)); ellipse(116, 37, 7, 7, accent); break;
      case 'Halo': ring(64, 8, 72, 20, col(0xF4D899), 4); ring(66, 10, 68, 16, col(WHITE, 0.5), 1.5); break;
      case 'Earpiece':
        ellipse(left + width - 6, 92, 10, 12, ink); ellipse(left + width - 3, 95, 4, 4, accent);
        line([p(left + width + 2, 104), p(left + width - 8, 120), p(112, 122)], op(ink, 0.8), 1.5); break;
      default: break;
    }

    // Necklines and garment details (no NOID badge: removed in 198).
    const tee = col(0x161D24);
    const openNeck = (depth) => {
      polygon([p(82, 145), p(118, 145), p(100, 145 + depth)], tee);
      smooth([p(86, 143), p(114, 143), p(100, 145 + depth * 0.55)], skin);
      smooth([p(88, 144), p(112, 144), p(100, 150)], col(BLACK, 0.10));
    };
    switch (c.clothing) {
      case 'Jacket':
        openNeck(36);
        polygon([p(72, 148), p(84, 143), p(99, 180), p(95, 200), p(78, 200), p(82, 168)], col(BLACK, 0.20));
        polygon([p(128, 148), p(116, 143), p(101, 180), p(105, 200), p(122, 200), p(118, 168)], col(BLACK, 0.20));
        line([p(84, 143), p(99, 180)], col(WHITE, 0.14), 1.2); line([p(116, 143), p(101, 180)], col(WHITE, 0.14), 1.2); break;
      case 'T-shirt':
        smooth([p(81, 144), p(119, 144), p(116, 153), p(100, 158), p(84, 153)], col(BLACK, 0.22));
        polygon([p(86, 140), p(114, 140), p(114, 150), p(100, 154), p(86, 150)], skin); break;
      case 'Turtleneck':
        smooth([p(80, 142), p(120, 142), p(122, 161), p(100, 164), p(78, 161)], col(0x2A2E38));
        for (const y of [149, 155]) line([p(82, y), p(118, y)], col(BLACK, 0.28), 1.3); break;
      case 'Suit':
        polygon([p(80, 146), p(120, 146), p(100, 184)], col(0xEFF2F3));
        smooth([p(89, 143), p(111, 143), p(100, 152)], skin);
        polygon([p(84, 144), p(93, 146), p(100, 156), p(90, 158)], col(0xDCE2E5));
        polygon([p(116, 144), p(107, 146), p(100, 156), p(110, 158)], col(0xDCE2E5));
        smooth([p(97, 156), p(103, 156), p(105, 176), p(100, 184), p(95, 176)], accent);
        polygon([p(72, 148), p(82, 144), p(99, 186), p(95, 200), p(78, 200), p(80, 168)], col(BLACK, 0.30));
        polygon([p(128, 148), p(118, 144), p(101, 186), p(105, 200), p(122, 200), p(120, 168)], col(BLACK, 0.30)); break;
      case 'Duster': case 'Trench': {
        const coat = col(c.clothing === 'Trench' ? 0x4A4133 : 0x7A5236);
        openNeck(33);
        polygon([p(68, 147), p(85, 142), p(100, 178), p(94, 200), p(76, 200), p(80, 166)], coat);
        polygon([p(132, 147), p(115, 142), p(100, 178), p(106, 200), p(124, 200), p(120, 166)], coat);
        line([p(85, 143), p(99, 176)], col(WHITE, 0.12), 1.2); line([p(115, 143), p(101, 176)], col(WHITE, 0.12), 1.2); break;
      }
      case 'Bomber':
        smooth([p(80, 143), p(120, 143), p(118, 152), p(100, 157), p(82, 152)], op(accent, 0.55));
        for (let x = 84; x <= 116; x += 4) line([p(x, 145), p(x, 151)], col(BLACK, 0.18), 1);
        line([p(100, 156), p(100, 200)], col(0xC7D2DD, 0.8), 1.6); break;
      case 'Tactical':
        openNeck(18);
        smooth([p(70, 156), p(130, 156), p(132, 200), p(68, 200)], col(0x3A4434));
        line([p(80, 148), p(78, 200)], col(BLACK, 0.35), 4); line([p(120, 148), p(122, 200)], col(BLACK, 0.35), 4);
        for (const x of [82, 103]) roundedBox(x, 168, 15, 13, 3, { fill: col(0x48543F) }); break;
      case 'Lab Coat':
        polygon([p(82, 145), p(118, 145), p(100, 176)], col(0x2A3640));
        smooth([p(86, 143), p(114, 143), p(100, 162)], skin);
        polygon([p(70, 148), p(85, 143), p(99, 178), p(95, 200), p(78, 200), p(82, 166)], col(0xF4F7F8));
        polygon([p(130, 148), p(115, 143), p(101, 178), p(105, 200), p(122, 200), p(118, 166)], col(0xF4F7F8));
        line([p(85, 143), p(99, 178)], col(BLACK, 0.14), 1.2); line([p(115, 143), p(101, 178)], col(BLACK, 0.14), 1.2);
        rect(72, 170, 2, 10, op(accent, 0.9)); break;
      case 'Armor': case 'Hero Armor':
        smooth([p(78, 142), p(122, 142), p(128, 158), p(100, 166), p(72, 158)], col(0x4C6470));
        smooth([p(82, 145), p(118, 145), p(122, 155), p(100, 161), p(78, 155)], col(0x3A505B));
        line([p(76, 158), p(100, 165), p(124, 158)], op(accent, 0.55), 1.5);
        if (c.clothing === 'Hero Armor') smooth([p(100, 168), p(108, 177), p(100, 186), p(92, 177)], op(accent, 0.85)); break;
      case 'Spacesuit':
        smooth([p(78, 140), p(122, 140), p(126, 152), p(100, 158), p(74, 152)], col(0xB9C8CB));
        line([p(80, 148), p(100, 153), p(120, 148)], col(BLACK, 0.18), 1.5); break;
      default: break;
    }
    if (hood) {
      // The hood's opening: a dark V from the jaw down into the hood, so the neck flows into the garment.
      smooth([p(76, 149), p(124, 149), p(127, 157), p(100, 170), p(73, 157)], col(0x101A21));
      smooth([p(70, 150), p(84, 152), p(100, 170), p(116, 152), p(130, 150), p(126, 160), p(100, 176), p(74, 160)], col(0x1B252C));
      const cord = col(0xC7D2DD, 0.85);
      line([p(92, 160), p(89, 176)], cord, 1.6); line([p(108, 160), p(111, 176)], cord, 1.6);
      roundedBox(87.5, 175, 3, 6, 1.5, { fill: cord }); roundedBox(109.5, 175, 3, 6, 1.5, { fill: cord });
    }
    if (['Chain', 'Double chain', 'Pendant'].includes(c.jewelry)) curve([p(84, 146), p(88, 160), p(100, 166), p(112, 160), p(116, 146)], gold, 2.4);
    if (c.jewelry === 'Double chain') curve([p(81, 148), p(86, 168), p(100, 176), p(114, 168), p(119, 148)], col(0xF4D899), 2);
    if (c.jewelry === 'Pendant') { line([p(100, 166), p(100, 169)], gold, 1.5); smooth([p(100, 168), p(105, 174), p(100, 181), p(95, 174)], accent); }

    // Finish: soft edge vignette and accent rim light inside the circular crop.
    defs.push(`<radialGradient id="${id}v" gradientUnits="userSpaceOnUse" cx="100" cy="100" r="101"><stop offset="0.7723" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity="0.22"/></radialGradient>`,
      `<clipPath id="${id}o"><circle cx="100" cy="100" r="100"/></clipPath>`);
    add(`<rect x="0" y="0" width="200" height="200" fill="url(#${id}v)"/>`);
    ring(1.5, 1.5, 197, 197, op(accent, 0.35), 2);
    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200"><defs>${defs.join('')}</defs><g clip-path="url(#${id}o)">${out.join('')}</g></svg>`;
  }

  function headOutline(left, width, ry, fem) {
    const rx = width / 2, cx = left + rx, cy = 45 + ry, r = left + width, k = 0.5523, b = 45 + ry * 2;
    const f = (v) => v.toFixed(2);
    const side = (m) => {
      const x = (dx) => f(cx + m * dx);
      if (fem) return `C${x(rx)} ${f(cy + ry * 0.42)} ${x(rx - 5.5)} ${f(b - 13)} ${x(12.5)} ${f(b - 4.2)} C${x(7.5)} ${f(b - 0.9)} ${x(3.5)} ${f(b)} ${f(cx)} ${f(b)}`;
      return `C${x(rx)} ${f(cy + ry * 0.36)} ${x(rx - 3.5)} ${f(b - 15)} ${x(rx - 13)} ${f(b - 6.5)} C${x(rx - 18.5)} ${f(b - 2)} ${x(7.5)} ${f(b)} ${f(cx)} ${f(b)}`;
    };
    const lx = (dx) => f(cx - dx);
    const left2 = fem
      ? `C${lx(3.5)} ${f(b)} ${lx(7.5)} ${f(b - 0.9)} ${lx(12.5)} ${f(b - 4.2)} C${lx(rx - 5.5)} ${f(b - 13)} ${lx(rx)} ${f(cy + ry * 0.42)} ${f(left)} ${f(cy)}`
      : `C${lx(7.5)} ${f(b)} ${lx(rx - 18.5)} ${f(b - 2)} ${lx(rx - 13)} ${f(b - 6.5)} C${lx(rx - 3.5)} ${f(b - 15)} ${lx(rx)} ${f(cy + ry * 0.36)} ${f(left)} ${f(cy)}`;
    return `M${f(left)} ${f(cy)} C${f(left)} ${f(cy - ry * k)} ${f(cx - rx * k)} 45 ${f(cx)} 45 C${f(cx + rx * k)} 45 ${f(r)} ${f(cy - ry * k)} ${f(r)} ${f(cy)} ${side(1)} ${left2} Z`;
  }
  function angularJaw(left, width, fem) {
    const r = left + width, f = (v) => v.toFixed(2);
    const chin = fem ? 9 : 13, b = fem ? 143 : 142;
    return `M${f(left)} 77 L${f(r)} 77 L${f(r - 1.5)} 111 C${f(r - 2.5)} 118 ${f(100 + chin + 6)} 136 ${f(100 + chin)} ${f(b - 2)} C${f(100 + chin - 3)} ${f(b)} ${f(103)} ${f(b + 0.5)} 100 ${f(b + 0.5)} C${f(97)} ${f(b + 0.5)} ${f(100 - chin + 3)} ${f(b)} ${f(100 - chin)} ${f(b - 2)} C${f(100 - chin - 6)} 136 ${f(left + 2.5)} 118 ${f(left + 1.5)} 111 Z`;
  }

  // ---- 48-cell pixel portrait (PixelCharacterAvatar.swift) ----
  function pixel(raw) {
    const c = decode(typeof raw === 'string' ? raw : JSON.stringify(raw));
    const out = [];
    const box = (x, y, w, h, k) => out.push(`<rect x="${x}" y="${y}" width="${w}" height="${h}" ${paint(k)}/>`);
    const accent = col(tint(c.accent));
    const palette = c.pixelTheme ?? 'Neon';
    const skin = col(SKIN[c.skin] ?? 0xDCA577);
    const hairHex = HAIR_COLOR[c.hairColor] ?? 0x171922;
    const hair = col(hairHex);
    const brows = (() => { const m = (s) => { const a = (hairHex >> s) & 255, b = (0x18212B >> s) & 255; return Math.round(a + (b - a) * 0.5); }; return col((m(16) << 16) | (m(8) << 8) | m(0)); })();
    const ink = col(0x111A22), gold = col(0xE4B94C), white = col(WHITE), black = (a) => col(BLACK, a);
    const special = isNonHuman(c);
    const backgrounds = { Paper: 0xD6E5DC, Ocean: 0x16394B, Plum: 0x30213F, Grid: 0x102C2A, Exchange: 0x18232B, Circuit: 0x102D37, Sunset: 0x674337, Starlight: 0x253956, Rose: 0x4D2E4A, Matrix: 0x04140C, Vault: 0x1A2026, Aurora: 0x0D1F2A, Skyline: 0x121A33, Blockchain: 0x0C1A1F, Void: 0x030507 };
    const back = col(backgrounds[c.background] ?? 0x09141D);
    box(0, 0, 48, 48, back);
    if (palette !== 'Shadow') for (let y = 4; y < 48; y += 8) box(0, y, 48, 1, op(accent, 0.14));
    if (palette === 'Circuit' || c.background === 'Grid') for (let x = 4; x < 48; x += 8) box(x, 0, 1, 48, op(accent, 0.12));
    if (palette === 'Arcade') for (let x = 2; x < 48; x += 8) { box(x, 5, 3, 2, op(accent, 0.24)); box(x + 3, 42, 2, 2, op(accent, 0.24)); }
    if (palette === 'Shadow') { box(0, 0, 5, 48, op(ink, 0.35)); box(43, 0, 5, 48, op(ink, 0.35)); }
    switch (c.background) {
      case 'Exchange': for (let x = 2; x < 48; x += 7) box(x, 36 - Math.trunc(x / 3), 5, 1, op(accent, 0.35)); break;
      case 'Starlight': for (let x = 3; x < 48; x += 9) box(x, (x * 17) % 34, 1, 1, col(WHITE, 0.8)); break;
      case 'Matrix': for (let x = 2; x < 48; x += 5) for (let k = 0; k < 4; k++) box(x, (x * 7 + k * 12) % 44, 1, 3, op(accent, 0.25)); break;
      case 'Vault':
        for (const [x, y] of [[4, 4], [40, 4], [4, 40], [40, 40]]) box(x, y, 4, 4, op(accent, 0.22));
        box(2, 22, 3, 4, op(accent, 0.22)); box(43, 22, 3, 4, op(accent, 0.22)); break;
      case 'Aurora': for (let x = 0; x < 48; x += 2) { box(x, 8 + Math.trunc((x % 8) / 2), 2, 3, op(accent, 0.22)); box(x, 14 + Math.trunc(((x + 4) % 8) / 2), 2, 2, col(0x8E5CD9, 0.25)); } break;
      case 'Skyline': for (const [x, h] of [[0, 14], [6, 20], [40, 18], [44, 12]]) { box(x, 48 - h, 5, h, col(0x0A1022)); box(x + 2, 48 - h + 3, 1, 1, op(accent, 0.5)); } break;
      case 'Blockchain':
        for (const x of [2, 14, 28, 40]) { box(x, 3, 6, 4, op(accent, 0.30)); box(x + 1, 4, 4, 2, back); }
        for (const x of [8, 20, 34]) box(x, 5, 6, 1, op(accent, 0.25)); break;
      default: break;
    }
    box(8, 8, 32, 29, op(accent, 0.10)); box(5, 13, 3, 19, op(accent, 0.07)); box(40, 13, 3, 19, op(accent, 0.07));
    const shoulder = c.presentation === 'Masculine' ? 7 : c.presentation === 'Feminine' ? 11 : 9;
    const outfits = { Spacesuit: 0xDCE4E6, Duster: 0x69452E, Trench: 0x3B3428, Turtleneck: 0x1C1F26, Bomber: 0x2F3B2A, Tactical: 0x2C3328, 'Lab Coat': 0xE8EEF0 };
    box(shoulder, 38, 48 - shoulder * 2, 10, ink); box(shoulder + 3, 35, 42 - shoulder * 2, 13, col(outfits[c.clothing] ?? 0x253744));
    if (c.clothing === 'Spacesuit') { box(9, 9, 30, 27, accent); box(11, 10, 26, 25, col(0xB9C8CB)); box(13, 12, 22, 22, ink); }
    if (!special) { box(21, 31, 7, 8, skin); box(21, 32, 7, 3, black(0.15)); }
    if (c.clothing === 'Hoodie' || c.style === 'Anonymous' || c.style === 'Hooded' || c.clothing === 'Full Anonymous') { box(11, 9, 26, 27, ink); box(13, 7, 22, 30, col(0x253744)); box(15, 10, 18, 24, ink); }
    if (!special) {
      if (c.hair === 'Long' || c.hair === 'Braids') box(13, 12, 22, 25, hair);
      if (c.hair === 'Bob') box(13, 11, 22, 19, hair);
      if (c.hair === 'Afro') { box(15, 3, 18, 2, hair); box(12, 5, 24, 2, hair); box(11, 7, 26, 12, hair); box(12, 19, 24, 2, hair); }
      if (c.hair === 'Ponytail') { box(33, 12, 4, 6, hair); box(35, 17, 3, 12, hair); }
    }
    const left = c.face === 'Round' ? 14 : 15, width = 48 - left * 2;
    switch (c.face) {
      case 'Alien': box(12, 12, 24, 14, skin); box(14, 9, 20, 4, skin); box(17, 7, 14, 3, skin); box(15, 26, 18, 4, skin); box(18, 30, 12, 3, skin); box(22, 33, 4, 2, skin); break;
      case 'Cat': box(13, 16, 22, 15, skin); box(16, 30, 16, 4, skin); box(13, 8, 6, 10, skin); box(29, 8, 6, 10, skin); box(15, 11, 2, 5, accent); box(31, 11, 2, 5, accent); break;
      case 'Robot': {
        const metal = col(0x9AA8B2), seam = col(0x6E7C86);
        box(21, 31, 7, 7, col(0x5E6B74)); box(21, 33, 7, 1, black(0.25)); box(21, 35, 7, 1, black(0.25));
        box(14, 10, 20, 22, metal); box(14, 10, 20, 1, seam); box(14, 31, 20, 1, seam); box(14, 10, 1, 22, seam); box(33, 10, 1, 22, seam);
        box(12, 18, 2, 7, seam); box(34, 18, 2, 7, seam); box(24, 6, 1, 4, seam); box(23, 4, 3, 2, accent);
        box(16, 18, 16, 6, ink); box(18, 27, 12, 2, ink); box(20, 27, 1, 2, metal); box(23, 27, 1, 2, metal); box(26, 27, 1, 2, metal);
        box(16, 12, 3, 1, col(WHITE, 0.5)); break;
      }
      default:
        box(left, 14, width, 15, skin); box(left + 2, 29, width - 4, 4, skin); box(left + 4, 33, width - 8, 1, skin);
        box(left - 2, 20, 2, 6, skin); box(48 - left, 20, 2, 6, skin);
        box(left, 16, 2, 12, col(WHITE, 0.12)); box(46 - left, 17, 2, 12, black(0.14));
    }
    if (c.face === 'Angular') { box(left, 28, 2, 3, ink); box(46 - left, 28, 2, 3, ink); }
    if (c.hair !== 'Bald' && !special) {
      switch (c.hair) {
        case 'Buzz': box(15, 11, 18, 4, op(hair, 0.9)); box(16, 10, 16, 1, op(hair, 0.9)); break;
        case 'Undercut': box(15, 12, 18, 3, op(hair, 0.45)); box(17, 8, 14, 5, hair); box(26, 7, 7, 3, hair); box(29, 12, 4, 4, hair); break;
        case 'Afro': box(13, 9, 22, 6, hair); break;
        case 'Mohawk': box(15, 13, 18, 2, op(hair, 0.35)); box(14, 14, 2, 4, op(hair, 0.35)); box(32, 14, 2, 4, op(hair, 0.35)); break;
        default: box(14, 10, 20, 5, hair); box(16, 8, 16, 3, hair); box(14, 14, 4, 4, hair); box(30, 13, 4, 5, hair);
      }
      if (c.hair === 'Crop') { box(18, 14, 9, 2, hair); box(18, 10, 10, 1, col(WHITE, 0.15)); }
      if (c.hair === 'Curls') for (let x = 12; x < 35; x += 4) { box(x, 9 + (x % 3), 4, 5, hair); box(x, 9 + (x % 3), 2, 1, col(WHITE, 0.12)); }
      if (c.hair === 'Mohawk') { box(21, 5, 7, 10, hair); box(22, 3, 5, 2, hair); box(22, 5, 2, 3, col(WHITE, 0.15)); }
      if (c.hair === 'Spikes') for (let x = 12; x < 37; x += 5) box(x, 7 - (x % 3), 3, 7, hair);
      if (c.hair === 'Braids') for (let y = 15; y < 36; y += 4) { box(12, y, 3, 3, hair); box(33, y, 3, 3, hair); }
      if (c.hair === 'Long') { box(13, 14, 3, 21, hair); box(32, 14, 3, 21, hair); }
      if (c.hair === 'Bob') { box(13, 14, 3, 15, hair); box(32, 14, 3, 15, hair); }
      if (c.hair === 'Bun') { box(21, 4, 7, 5, hair); box(22, 5, 2, 1, col(WHITE, 0.15)); }
    }
    switch (c.face) {
      case 'Cat':
        box(18, 21, 4, 3, ink); box(27, 21, 4, 3, ink); box(23, 27, 3, 2, ink);
        box(17, 28, 6, 1, op(ink, 0.6)); box(26, 28, 6, 1, op(ink, 0.6)); box(12, 26, 6, 1, op(ink, 0.4)); box(30, 26, 6, 1, op(ink, 0.4)); break;
      case 'Alien': box(14, 19, 9, 4, ink); box(16, 23, 5, 2, ink); box(25, 19, 9, 4, ink); box(27, 23, 5, 2, ink); box(22, 30, 4, 1, op(ink, 0.7)); break;
      case 'Robot': box(18, 19, 4, 4, accent); box(26, 19, 4, 4, accent); box(18, 19, 1, 1, col(WHITE, 0.85)); box(26, 19, 1, 1, col(WHITE, 0.85)); break;
      default:
        box(17, 19, 5, 1, brows); box(27, 19, 5, 1, brows);
        switch (c.eyes) {
          case 'Glow': box(17, 20, 5, 4, op(accent, 0.35)); box(27, 20, 5, 4, op(accent, 0.35)); box(18, 21, 3, 2, accent); box(28, 21, 3, 2, accent); break;
          case 'Sly': box(18, 22, 3, 1, ink); box(28, 22, 3, 1, ink); break;
          case 'Wink': box(18, 21, 3, 2, ink); box(18, 21, 1, 1, white); box(28, 22, 3, 1, ink); break;
          default: box(18, 21, 3, 2, ink); box(28, 21, 3, 2, ink); box(18, 21, 1, 1, white); box(28, 21, 1, 1, white);
        }
        if (c.eyes === 'Wide') { box(18, 20, 3, 1, ink); box(28, 20, 3, 1, ink); }
        if (c.eyes === 'Focused') { box(20, 20, 2, 1, hair); box(26, 20, 2, 1, hair); }
        if (c.presentation === 'Feminine') { box(17, 20, 1, 1, ink); box(31, 20, 1, 1, ink); }
        box(24, 23, 1, 4, black(0.18)); box(23, 26, 3, 1, black(0.22));
        switch (c.facialHair) {
          case 'Beard':
            box(left, 23, 2, 7, hair); box(46 - left, 23, 2, 7, hair);
            box(left + 1, 28, width - 2, 4, hair); box(left + 2, 32, width - 4, 1, hair); box(left + 4, 33, width - 8, 2, hair);
            box(left + 2, 33, width - 4, 1, black(0.18)); box(20, 27, 9, 1, hair);
            box(21, 29, 7, 1, col(0x8C4F45)); break;
          case 'Stubble': for (let y = 27; y < 34; y++) for (let x = left + 1 + (y % 2); x < 47 - left; x += 2) box(x, y, 1, 1, op(hair, 0.2)); break;
          case 'Mustache': box(20, 27, 9, 1, hair); box(21, 26, 7, 1, op(hair, 0.6)); break;
          case 'Goatee': box(20, 27, 9, 1, hair); box(21, 26, 7, 1, op(hair, 0.6)); box(22, 31, 5, 2, hair); box(23, 33, 3, 1, hair); break;
          default: break;
        }
        box(21, 29, 7, 1, op(ink, c.facialHair === 'Beard' ? 0.55 : 0.7)); if (c.eyes === 'Friendly' || c.eyes === 'Wink') box(22, 30, 5, 1, op(ink, 0.7));
    }
    if (c.style === 'Anonymous' || c.accessory === 'Mask') {
      box(15, 17, 19, 14, col(0xE7EBE5)); box(17, 31, 15, 3, col(0xC4D1CA));
      box(19, 34, 11, 2, col(0xDCE5DE)); box(22, 36, 5, 1, col(0xDCE5DE));
      box(17, 20, 6, 1, ink); box(26, 20, 6, 1, ink);
      box(18, 22, 5, 2, ink); box(26, 22, 5, 2, ink);
      box(23, 24, 3, 4, col(0xA9B9B4));
      box(17, 27, 3, 1, col(0xB97982)); box(29, 27, 3, 1, col(0xB97982));
      box(20, 29, 9, 1, ink); box(18, 28, 2, 1, ink); box(29, 28, 2, 1, ink);
      box(22, 32, 5, 1, ink); box(23, 34, 3, 2, ink); box(24, 36, 1, 2, ink);
    }
    if (['Glasses', 'Shades', 'Sunglasses', 'Visor'].includes(c.accessory)) {
      box(15, 20, 19, 1, accent); box(16, 21, 7, 4, ink); box(26, 21, 7, 4, ink); box(23, 22, 3, 1, accent);
      if (c.accessory === 'Glasses') { box(17, 21, 5, 3, op(skin, 0.65)); box(27, 21, 5, 3, op(skin, 0.65)); }
      if (c.accessory === 'Visor') { box(16, 20, 17, 5, accent); box(17, 21, 13, 1, col(WHITE, 0.7)); }
    }
    switch (c.accessory) {
      case 'Monocle': box(26, 19, 6, 1, gold); box(26, 24, 6, 1, gold); box(26, 19, 1, 6, gold); box(31, 19, 1, 6, gold); box(31, 25, 1, 8, gold); break;
      case 'Eyepatch': box(15, 16, 18, 1, ink); box(26, 19, 6, 5, ink); break;
      case 'Cyber Eye': box(25, 19, 8, 6, col(0x2A3640)); box(27, 20, 4, 4, accent); box(29, 21, 1, 1, col(WHITE, 0.9)); box(33, 22, 3, 1, op(accent, 0.8)); break;
      case 'Bandana': box(15, 26, 19, 6, op(accent, 0.9)); box(17, 32, 15, 2, op(accent, 0.9)); box(19, 28, 1, 1, col(WHITE, 0.4)); box(24, 29, 1, 1, col(WHITE, 0.4)); box(29, 28, 1, 1, col(WHITE, 0.4)); break;
      case 'Respirator': box(18, 27, 13, 7, col(0x2C3338)); box(15, 28, 4, 4, col(0x4A555D)); box(30, 28, 4, 4, col(0x4A555D)); box(16, 29, 2, 2, op(accent, 0.7)); box(31, 29, 2, 2, op(accent, 0.7)); break;
      case 'Earpiece': box(34, 22, 2, 3, ink); box(34, 23, 1, 1, accent); break;
      case 'Beanie': box(14, 9, 20, 7, accent); box(16, 7, 16, 3, accent); box(14, 15, 20, 2, ink); break;
      case 'Cap': box(14, 10, 20, 6, ink); box(16, 8, 16, 3, ink); box(13, 15, 25, 2, accent); break;
      case 'Cowboy Hat': box(15, 7, 18, 8, col(0x69452E)); box(9, 15, 30, 3, col(0x69452E)); box(15, 13, 18, 2, accent); break;
      case 'Helmet': box(9, 10, 4, 24, white); box(35, 10, 4, 24, white); box(13, 7, 22, 4, white); box(13, 34, 22, 3, white); box(14, 11, 4, 2, op(accent, 0.7)); break;
      case 'Piercing': box(34, 24, 1, 3, accent); break;
      case 'Headphones': box(10, 19, 4, 11, accent); box(34, 19, 4, 11, accent); box(13, 12, 3, 3, accent); box(32, 12, 3, 3, accent); box(16, 10, 16, 2, accent); break;
      case 'Crown':
        box(16, 5, 16, 4, gold); box(16, 2, 2, 3, gold); box(23, 1, 2, 4, gold); box(30, 2, 2, 3, gold);
        box(19, 6, 2, 2, accent); box(27, 6, 2, 2, accent); box(23, 6, 2, 2, col(0xF06C73)); break;
      case 'Halo': { const light = col(0xF4D899); box(17, 2, 14, 1, light); box(16, 3, 1, 1, light); box(31, 3, 1, 1, light); box(17, 4, 14, 1, light); break; }
      default: break;
    }
    switch (c.clothing) {
      case 'Armor': case 'Hero Armor':
        box(8, 38, 32, 4, op(accent, 0.55)); box(14, 42, 20, 2, accent);
        if (c.clothing === 'Hero Armor') { box(21, 39, 6, 5, accent); box(23, 40, 2, 3, ink); } break;
      case 'Suit': box(12, 38, 24, 10, col(0x171E2C)); box(19, 38, 10, 8, white); box(23, 40, 2, 7, accent); break;
      case 'Trench': box(shoulder + 3, 37, 6, 11, col(0x4A4133)); box(39 - shoulder, 37, 6, 11, col(0x4A4133)); box(shoulder + 3, 44, 42 - shoulder * 2, 1, col(0x2A251C)); break;
      case 'Turtleneck': box(20, 33, 9, 5, col(0x2A2E38)); box(20, 35, 9, 1, black(0.35)); break;
      case 'Bomber': box(18, 36, 13, 2, op(accent, 0.8)); box(24, 38, 1, 10, col(0xC7D2DD)); box(shoulder + 5, 41, 3, 2, op(accent, 0.6)); break;
      case 'Tactical': box(15, 38, 19, 10, col(0x3A4434)); for (const x of [16, 22, 28]) box(x, 41, 4, 3, col(0x48543F)); box(15, 38, 19, 1, op(accent, 0.8)); break;
      case 'Lab Coat': box(19, 37, 11, 11, col(0x2A3640)); box(19, 37, 1, 11, col(0xB8C4C8)); box(29, 37, 1, 11, col(0xB8C4C8)); box(31, 41, 3, 1, accent); break;
      default: break;
    }
    if (c.clothing === 'T-shirt') box(shoulder + 3, 39, 42 - shoulder * 2, 9, op(accent, 0.75));
    else if (['Jacket', 'Duster', 'Hoodie', 'Full Anonymous'].includes(c.clothing)) {
      const tee = col(0x161D24);
      box(21, 37, 7, 2, tee); box(22, 39, 5, 2, tee); box(23, 41, 3, 2, tee);
      box(22, 37, 5, 1, skin); box(23, 38, 3, 1, skin);
      if (c.clothing === 'Hoodie' || c.clothing === 'Full Anonymous') { box(21, 39, 1, 5, col(0xC7D2DD, 0.85)); box(27, 39, 1, 5, col(0xC7D2DD, 0.85)); }
      else { box(18, 37, 3, 9, black(0.22)); box(28, 37, 3, 9, black(0.22)); }
      box(shoulder + 4, 43, 5, 1, col(WHITE, 0.12));
    }
    if (['Chain', 'Double chain', 'Pendant'].includes(c.jewelry)) for (const [x, y] of [[17, 37], [20, 38], [23, 39], [26, 38], [29, 37]]) box(x, y, 3, 1, gold);
    if (c.jewelry === 'Double chain') box(20, 40, 9, 1, col(0xF4D899));
    if (c.jewelry === 'Pendant') box(23, 40, 3, 3, accent);
    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48" shape-rendering="crispEdges">${out.join('')}</svg>`;
  }

  const cache = new Map();
  /** SVG markup for a recipe (vector or pixel, as the recipe says). */
  function svg(raw) {
    const c = decode(typeof raw === 'string' ? raw : JSON.stringify(raw ?? {}));
    const key = JSON.stringify(encode(c));
    if (cache.has(key)) return cache.get(key);
    const markup = isPixel(c) ? pixel(c) : vector(c);
    if (cache.size > 128) cache.delete(cache.keys().next().value);
    cache.set(key, markup); return markup;
  }
  const uri = (raw) => `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg(raw))}`;
  return Object.freeze({ options: O, DEFAULT, decode, encode, valid, preset, isPixel, isNonHuman, removeHumanLayers, svg, uri, tint, skinColors: SKIN, hairColors: HAIR_COLOR });
})();
