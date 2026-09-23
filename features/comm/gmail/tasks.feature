Feature: Gmail task routes — mail becomes a hail on a band
  A route with :action :task sends the message as a hail on a band instead of
  starting a thread session. The message is labelled isaac/<route> first (the
  same idempotency check as any other route), then a hail carries the mail as
  params. Hail is a separate module Isaac resolves at runtime — a task route
  degrades to a warn log and an /unsent label when it is not installed, never
  an exception. A task route only fires for senders the gate admitted, and a
  *@domain :match :from only admits a sender Gmail authenticated. Bean: isaac-3427.

  Background:
    Given default Grover setup in "/test/gmail-tasks"
    And config:
      | log.output                | memory           |
      | google.tonotop.project    | marigold         |
      | comms.gmail.gmail/account | yopp@tonotop.com |
      | comms.gmail.gmail/crew    | main             |
      | sessions.naming-strategy  | sequential       |
    And the google auth store has access "at-1" and refresh "rt-1"
    And the gmail history cursor is "1000"

  @wip
  Scenario: a task route sends one hail with the band and message payload, labelled before the hail
    Given the hail module is installed
    And config:
      | gmail-routes.invoices.order         | 20              |
      | gmail-routes.invoices.match.from    | *@tonotop.com   |
      | gmail-routes.invoices.match.subject | (?i)\binvoice\b |
      | gmail-routes.invoices.action        | task            |
      | gmail-routes.invoices.band          | ops-inbox       |
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from    | ada@tonotop.com   |
      | to      | yopp@tonotop.com  |
      | subject | Invoice #4821     |
      | body    | Please file this. |
    When Gmail pushes a watch notification with history id "1042"
    Then message "m-1" carries label "isaac/invoices"
    And one hail was sent to band "ops-inbox" with:
      | path             | value             |
      | gmail/id         | m-1               |
      | gmail/thread-id  | t-1               |
      | from             | ada@tonotop.com   |
      | subject          | Invoice #4821     |
      | body-excerpt     | Please file this. |
    And the session count is 0

  @wip
  Scenario: a message already labelled for its task route is not hailed twice
    Given the hail module is installed
    And config:
      | gmail-routes.invoices.order         | 20              |
      | gmail-routes.invoices.match.from    | *@tonotop.com   |
      | gmail-routes.invoices.match.subject | (?i)\binvoice\b |
      | gmail-routes.invoices.action        | task            |
      | gmail-routes.invoices.band          | ops-inbox       |
    And message "m-1" already carries label "isaac/invoices"
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from    | ada@tonotop.com   |
      | to      | yopp@tonotop.com  |
      | subject | Invoice #4821     |
      | body    | Please file this. |
    When Gmail pushes a watch notification with history id "1042"
    Then no hail was sent

  @wip
  Scenario: an ack route replies on the thread once; the default sends no reply
    Given the hail module is installed
    And config:
      | gmail-routes.invoices.order         | 20              |
      | gmail-routes.invoices.match.from    | *@tonotop.com   |
      | gmail-routes.invoices.match.subject | (?i)\binvoice\b |
      | gmail-routes.invoices.action        | task            |
      | gmail-routes.invoices.band          | ops-inbox       |
      | gmail-routes.invoices.ack           | true            |
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from       | ada@tonotop.com   |
      | to         | yopp@tonotop.com  |
      | subject    | Invoice #4821     |
      | message-id | <inv@tonotop.com> |
      | body       | Please file this. |
    When Gmail pushes a watch notification with history id "1042"
    Then an outbound HTTP request to "https://gmail.googleapis.com/gmail/v1/users/me/messages/send" matches:
      | method        | POST |
      | body.threadId | t-1  |
    And the sent mail decodes to:
      | To      | ada@tonotop.com               |
      | Subject | Re: Invoice #4821             |
      | text    | #"Got it .* Invoice #4821.*"  |

    Given config:
      | gmail-routes.invoices.ack | false |
    And the Gmail API history since "1042" adds messages:
      | id  | threadId |
      | m-2 | t-2      |
    And the Gmail API returns message "m-2":
      | from    | ada@tonotop.com  |
      | to      | yopp@tonotop.com |
      | subject | Invoice #4822    |
      | body    | Another one.     |
    When Gmail pushes a watch notification with history id "1099"
    Then no outbound HTTP request to "https://gmail.googleapis.com/gmail/v1/users/me/messages/send" was made

  @wip
  Scenario: a task route without the hail module installed logs a warning and marks the message unsent, no exception
    Given config:
      | gmail-routes.invoices.order         | 20              |
      | gmail-routes.invoices.match.from    | *@tonotop.com   |
      | gmail-routes.invoices.match.subject | (?i)\binvoice\b |
      | gmail-routes.invoices.action        | task            |
      | gmail-routes.invoices.band          | ops-inbox       |
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from    | ada@tonotop.com   |
      | to      | yopp@tonotop.com  |
      | subject | Invoice #4821     |
      | body    | Please file this. |
    When Gmail pushes a watch notification with history id "1042"
    Then message "m-1" carries label "isaac/invoices/unsent"
    And the log has entries matching:
      | level | event                         |
      | :warn | :gmail.route/hail-unavailable |
    And the session count is 0

  @wip
  Scenario: the body excerpt sent with a task hail is capped
    Given the hail module is installed
    And config:
      | gmail-routes.invoices.order         | 20              |
      | gmail-routes.invoices.match.from    | *@tonotop.com   |
      | gmail-routes.invoices.match.subject | (?i)\binvoice\b |
      | gmail-routes.invoices.action        | task            |
      | gmail-routes.invoices.band          | ops-inbox       |
      | comms.gmail.gmail/task-body-cap     | 20              |
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from    | ada@tonotop.com                                           |
      | to      | yopp@tonotop.com                                          |
      | subject | Invoice #4821                                             |
      | body    | This message body is much longer than twenty characters. |
    When Gmail pushes a watch notification with history id "1042"
    Then one hail was sent to band "ops-inbox" with:
      | path         | value                |
      | body-excerpt | This message body is |

  @wip
  Scenario: a task route with a *@domain pattern only fires when Gmail authenticates the sender
    Given the hail module is installed
    And config:
      | gmail-routes.invoices.order         | 20              |
      | gmail-routes.invoices.match.from    | *@tonotop.com   |
      | gmail-routes.invoices.match.subject | (?i)\binvoice\b |
      | gmail-routes.invoices.action        | task            |
      | gmail-routes.invoices.band          | ops-inbox       |
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
      | m-2 | t-2      |
    And the Gmail API returns message "m-1":
      | from         | Ada Lovelace <ada@tonotop.com>                                                                                        |
      | to           | yopp@tonotop.com                                                                                                      |
      | subject      | Invoice #4821                                                                                                         |
      | body         | Please file this.                                                                                                     |
      | auth-results | mx.google.com; dkim=pass header.d=tonotop.com; spf=pass smtp.mailfrom=tonotop.com; dmarc=pass header.from=tonotop.com |
    And the Gmail API returns message "m-2":
      | from         | Ada Lovelace <ada@tonotop.com>                                             |
      | to           | yopp@tonotop.com                                                           |
      | subject      | Invoice #9999 urgent                                                       |
      | body         | Wire the funds now.                                                        |
      | auth-results | mx.google.com; dkim=none; spf=softfail; dmarc=fail header.from=tonotop.com |
    When Gmail pushes a watch notification with history id "1099"
    Then message "m-1" carries label "isaac/invoices"
    And one hail was sent to band "ops-inbox" with:
      | path     | value |
      | gmail/id | m-1   |
    And the log has entries matching:
      | level | event                  | reason           |
      | :warn | :gmail/message-dropped | :unauthenticated |
