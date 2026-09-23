Feature: Gmail triage fallback for unrouted mail
  When gmail/triage is configured, an unrouted message (one no route claims)
  runs one turn in a dedicated, reset-context session gmail-triage instead of
  going straight to :unrouted. The model picks one route name or "ignore";
  anything else falls back to gmail/triage.default. The verdict is always
  recorded as label isaac/triage/<verdict>. With gmail/triage.apply false
  (the default) that is all — Micah audits verdicts before trusting them.
  With apply true the message is dispatched as if that route had matched.
  Bean: isaac-betb.

  Background:
    Given default Grover setup in "/test/gmail-triage"
    And config:
      | log.output                   | memory         |
      | google.tonotop.project       | marigold       |
      | comms.gmail.gmail/account    | yopp@tonotop.com |
      | comms.gmail.gmail/crew       | main           |
      | sessions.naming-strategy     | sequential     |
      | gmail-routes.team.order      | 90             |
      | gmail-routes.team.match.from | *@tonotop.com  |
      | gmail-routes.team.action     | converse       |
      | gmail-routes.team.crew       | main           |
      | gmail-routes.team.desc       | Colleagues     |
    And the google auth store has access "at-1" and refresh "rt-1"
    And the gmail history cursor is "1000"

  Scenario: an unrouted message runs one triage turn and labels the verdict without dispatching it
    Given config:
      | gmail/triage.model   | echo                            |
      | gmail/triage.crew    | main                             |
      | gmail/triage.choices | ["team" "newsletters" "ignore"] |
      | gmail/triage.default | ignore                          |
      | gmail/triage.apply   | false                            |
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from    | ada@tonotop.com |
      | to      | yopp@tonotop.com |
      | subject | Quick question  |
      | body    | Are you free?   |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | team    |
    When Gmail pushes a watch notification with history id "1042"
    Then session "gmail-triage" has transcript matching:
      | type    | message.role | message.content                       |
      | message | user         | #".*Quick question.*Are you free\?.*" |
      | message | assistant    | team                                   |
    And the last LLM request matches:
      | model | echo |
    And message "m-1" carries label "isaac/triage/team"
    And the session count is 1

  Scenario: apply true dispatches the message as if the verdict route had matched
    Given config:
      | gmail/triage.model   | echo                            |
      | gmail/triage.crew    | main                             |
      | gmail/triage.choices | ["team" "newsletters" "ignore"] |
      | gmail/triage.default | ignore                          |
      | gmail/triage.apply   | true                             |
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from    | ada@tonotop.com |
      | to      | yopp@tonotop.com |
      | subject | Quick question  |
      | body    | Are you free?   |
    And the following model responses are queued:
      | model | type | content   |
      | echo  | text | team      |
      | model | type | content   |
      | echo  | text | Yes, 3pm. |
    When Gmail pushes a watch notification with history id "1042"
    Then message "m-1" carries label "isaac/triage/team"
    And message "m-1" carries label "isaac/team"
    And session "gmail-t-1" has transcript matching:
      | type    | message.role | message.crew | message.content       |
      | message | user         | main         | #".*Are you free\?.*" |
      | message | assistant    | main         | Yes, 3pm.              |

  Scenario: a verdict outside the configured choices falls back to the default
    Given config:
      | gmail/triage.model   | echo                            |
      | gmail/triage.crew    | main                             |
      | gmail/triage.choices | ["team" "newsletters" "ignore"] |
      | gmail/triage.default | ignore                          |
      | gmail/triage.apply   | false                            |
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from    | mallory@example.com |
      | to      | yopp@tonotop.com    |
      | subject | Prize               |
      | body    | You won             |
    And the following model responses are queued:
      | model | type | content  |
      | echo  | text | vacation |
    When Gmail pushes a watch notification with history id "1042"
    Then message "m-1" carries label "isaac/triage/ignore"

  Scenario: the triage session resets between messages instead of accumulating transcript
    Given config:
      | gmail/triage.model   | echo                            |
      | gmail/triage.crew    | main                             |
      | gmail/triage.choices | ["team" "newsletters" "ignore"] |
      | gmail/triage.default | ignore                          |
      | gmail/triage.apply   | false                            |
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from    | ada@tonotop.com  |
      | to      | yopp@tonotop.com |
      | subject | First one        |
      | body    | Hello            |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | team    |
    When Gmail pushes a watch notification with history id "1042"

    Given the Gmail API history since "1042" adds messages:
      | id  | threadId |
      | m-2 | t-2      |
    And the Gmail API returns message "m-2":
      | from    | ada@tonotop.com  |
      | to      | yopp@tonotop.com |
      | subject | Second one       |
      | body    | Hi again         |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | team    |
    When Gmail pushes a watch notification with history id "1099"
    Then message "m-2" carries label "isaac/triage/team"
    And session "gmail-triage" has transcript matching:
      | #index | type    | message.role | message.content   |
      | 0      | message | user         | #".*Second one.*" |
      | 1      | message | assistant    | team               |

  Scenario: without gmail/triage configured, an unrouted message behaves as in the routes bean
    Given the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from    | bob@example.com |
      | to      | yopp@tonotop.com |
      | subject | Random thought  |
      | body    | Did you see this? |
    When Gmail pushes a watch notification with history id "1042"
    Then message "m-1" carries label "isaac/unrouted"
    And the session count is 0
