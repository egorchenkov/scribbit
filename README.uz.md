# Scribbit — Android uchun oflayn nutqni matnga aylantirish

[English](README.md) · [Русский](README.ru.md) · [Sayt](https://egorchenkov.github.io/scribbit/)

*Tarjima qoralama; xatolarni Issues orqali xabar qiling.*

Scribbit uchrashuv yozuvini, Telegram ovozli xabarini yoki istalgan audio faylni **toʻgʻridan-toʻgʻri
telefonda** matnga aylantiradi. Internet faqat bir marta — modelni yuklab olish uchun kerak; keyin
hech narsa qurilmadan chiqmaydi. Bepul, ochiq kodli (MIT).

## Imkoniyatlar
- **Oʻzbekcha / qozoqcha** (GigaAM Multilingual), **ruscha** (GigaAM v3 — tinish belgilari bilan),
  **inglizcha** va ~100 boshqa til (Whisper Small).
- **Soʻzlovchilarni ajratish** («1-soʻzlovchi: …») — uchrashuvlar va qoʻngʻiroqlar uchun;
  ishtirokchilar sonini koʻrsatish yoki «avto» qoldirish mumkin. Koʻp soatli yozuvlar qismlarga
  boʻlib ishlanadi, xotira fayl uzunligi bilan oʻsmaydi.
- Fonda mikrofondan yozib olish (ekranni oʻchirish mumkin), bir nechta faylni birga ishlash,
  Telegram, WhatsApp, diktofon va boshqa ilovalardan «Ulashish» (audio va video).
- Natija: nusxalash, matn sifatida yuborish (messenjer yoki LLM ga), .txt saqlash; vaqt belgilari ixtiyoriy.
- Tizim jarayonni toʻxtatsa ham ish oxirgi tayyor 5 daqiqalik qismdan davom etadi.
- Interfeys oʻzbek, rus va ingliz tillarida (tizim tiliga qarab).

## Oʻrnatish
1. [Releases](../../releases) sahifasidan `Scribbit-X.Y.Z.apk` ni yuklab oling va telefonda oching
   (shu manbadan oʻrnatishga ruxsat bering). Android 8+ va 64-bitli ARM (arm64-v8a) kerak —
   soʻnggi yillardagi deyarli barcha telefonlar.
2. Sozlamalar (tishli belgi) → nutq modelini, uchrashuvlar uchun esa «Soʻzlovchilarni ajratish»ni yuklab oling.
3. «● Yozish», «Fayllarni tanlash» yoki «Ulashish» → «Transkripsiya».

Yangilanishlar uchun bu repozitoriyni [Obtainium](https://github.com/ImranR98/Obtainium) ga qoʻshing.

## Modellar
Ilova ichida HuggingFace dan yuklab olinadi; APK tarkibiga kirmaydi.

- GigaAM Multilingual CTC — oʻzbekcha / ruscha / qozoqcha, MIT.
- GigaAM v3 punct CTC — ruscha, Sber, MIT.
- Whisper Small (int8) — OpenAI, MIT.
- Soʻzlovchilarni ajratish: pyannote segmentation-3.0 (MIT) + NVIDIA TitaNet-small (CC BY 4.0).
- Nutq detektori Silero VAD (MIT) — APK ichida.

## Maxfiylik
Ilova audio va matnni hech qayerga yubormaydi, analitika yoʻq. Tarmoq faqat tugma bosilganda
modellarni yuklab olish uchun ishlatiladi.

## Litsenziya
MIT — `LICENSE` ga qarang. Modellar oʻz litsenziyalari bilan tarqatiladi (yuqoridagi roʻyxat).
