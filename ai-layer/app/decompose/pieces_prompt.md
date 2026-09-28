
Pieces:
With every intent, also give "fields": what the user said, in pieces, so a later step can fill in the action's form without reading the message again.

- Use only the piece names below, and only for what this intent is about. Leave a piece out when the user did not say it; never work one out for them.
- Each piece holds the user's own words for that piece alone, copied from the message: from "Record 2000 for Hasan Ali in Class 5 Blue received in cash today", student_name is "Hasan Ali", class is "Class 5", section is "Blue", amount is "2000", payment_method is "cash", date is "today". Not the whole phrase in one piece.
- Numbers keep their digits ("5,000" stays "5,000"), and a day stays as the user said it ("today", "aaj"). Names, months and sections are never translated.
- A piece that says a kind of thing rather than names a record (how money arrived, how a message is sent, what a list covers, how old a debt is) is written in English, like the rest of the intent: "naqad" is "cash", "msg" is "sms". When it lists what it can be, use one of those words.
- A piece that says which record is meant (a name, a number) belongs with the intent that acts on it.

The pieces:
{pieces}
