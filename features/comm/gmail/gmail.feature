Feature: Gmail comm
  A Gmail push carries only a history id. Isaac walks history from the
  last id it processed, fetches the new INBOX messages, gates them, and
  routes each thread to one session. Replies go out on the thread with
  the headers mail clients need. The push step hands the watch payload to
  the handler this module contributed to :isaac.google/handler, the way
  the Chat steps do. Bean: isaac-cr0o. Senders are admitted by gmail-routes
  (isaac-sb6d), not by an allow-list.

  Background:
    Given default Grover setup in "/test/gmail"
    And config:
      | log.output                   | memory              |
      | google.tonotop.project       | marigold            |
      | comms.gmail.gmail/account    | yopp@tonotop.com    |
      | comms.gmail.gmail/crew       | main                |
      | gmail-routes.team.order      | 90                  |
      | gmail-routes.team.match.from | ada@tonotop.com     |
      | gmail-routes.team.action     | converse            |
      | sessions.naming-strategy     | sequential          |
    And the google auth store has access "at-1" and refresh "rt-1"
    And the gmail history cursor is "1000"

  Scenario: a watch push after two new INBOX messages starts one turn per thread
    Given the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
      | m-2 | t-2      |
    And the Gmail API returns message "m-1":
      | from    | ada@tonotop.com     |
      | to      | yopp@tonotop.com    |
      | subject | Deploy window       |
      | body    | Can we ship Friday? |
    And the Gmail API returns message "m-2":
      | from    | ada@tonotop.com  |
      | to      | yopp@tonotop.com |
      | subject | Lunch            |
      | body    | Tacos?           |
    And the following model responses are queued:
      | model | type | content       |
      | echo  | text | Friday works. |
      | model | type | content       |
      | echo  | text | Yes.          |
    When Gmail pushes a watch notification with history id "1042"
    Then session "gmail-t-1" has transcript matching:
      | type    | message.role | message.content                                    |
      | message | user         | #".*ada@tonotop.com.*Deploy window.*ship Friday.*" |
      | message | assistant    | Friday works.                                      |
    And session "gmail-t-2" has transcript matching:
      | type    | message.role | message.content     |
      | message | user         | #".*Lunch.*Tacos.*" |
      | message | assistant    | Yes.                |
    And the gmail history cursor is "1042"

  Scenario: a reply goes out on the originating thread with the headers clients need
    Given the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from       | ada@tonotop.com     |
      | to         | yopp@tonotop.com    |
      | subject    | Deploy window       |
      | message-id | <abc@tonotop.com>   |
      | body       | Can we ship Friday? |
    And the following model responses are queued:
      | model | type | content       |
      | echo  | text | Friday works. |
    When Gmail pushes a watch notification with history id "1042"
    Then an outbound HTTP request to "https://gmail.googleapis.com/gmail/v1/users/me/messages/send" matches:
      | method                | POST        |
      | headers.Authorization | Bearer at-1 |
      | body.threadId         | t-1         |
    And the sent mail decodes to:
      | To          | ada@tonotop.com   |
      | Subject     | Re: Deploy window |
      | In-Reply-To | <abc@tonotop.com> |
      | References  | <abc@tonotop.com> |
      | text        | Friday works.     |

  Scenario: an already-processed push starts nothing
    Given the gmail history cursor is "1042"
    When Gmail pushes a watch notification with history id "1042"
    Then the session count is 0
    And grover records zero provider requests
    And no outbound HTTP request to "https://gmail.googleapis.com/gmail/v1/users/me/history" was made

  Scenario: a stale cursor resyncs from the inbox and continues
    Given the Gmail API history since "1000" is gone
    And the Gmail API inbox lists messages:
      | id  | threadId | historyId |
      | m-7 | t-7      | 2001      |
    And the Gmail API returns message "m-7":
      | from    | ada@tonotop.com  |
      | to      | yopp@tonotop.com |
      | subject | After the gap    |
      | body    | Still there?     |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | Yes.    |
    When Gmail pushes a watch notification with history id "2050"
    Then the log has entries matching:
      | level | event         | from | to   |
      | :warn | :gmail/resync | 1000 | 2001 |
    And session "gmail-t-7" has transcript matching:
      | type    | message.role | message.content       |
      | message | user         | #".*After the gap.*"  |
      | message | assistant    | Yes.                  |
    And the gmail history cursor is "2001"

  Scenario: sent mail, label-only changes, and senders no route names never start a turn
    Given the Gmail API history since "1000" contains:
      | kind         | id  | threadId | labelIds   |
      | labelAdded   | m-3 | t-3      | STARRED    |
      | messageAdded | m-4 | t-4      | SENT       |
      | messageAdded | m-5 | t-5      | INBOX      |
    And the Gmail API returns message "m-5":
      | from    | mallory@example.com |
      | to      | yopp@tonotop.com    |
      | subject | Prize               |
      | body    | You won             |
    When Gmail pushes a watch notification with history id "1099"
    Then the session count is 0
    And grover records zero provider requests
    And the log has entries matching:
      | level  | event                  | reason     |
      | :debug | :gmail/message-dropped | :not-inbox |
    And the log has entries matching:
      | level | event           | from                |
      | :info | :gmail/unrouted | mallory@example.com |
    And message "m-5" carries label "isaac/unrouted"
    And the gmail history cursor is "1099"

  Scenario: a *@domain route admits the domain only when Gmail authenticates it (isaac-dymn, isaac-sb6d)
    Given config:
      | gmail-routes.domain.order      | 10            |
      | gmail-routes.domain.match.from | *@tonotop.com |
      | gmail-routes.domain.action     | converse      |
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-6 | t-6      |
      | m-7 | t-7      |
    And the Gmail API returns message "m-6":
      | from         | Grace Hopper <grace@tonotop.com>                                                                          |
      | to           | yopp@tonotop.com                                                                                          |
      | subject      | Ship it                                                                                                   |
      | body         | Friday?                                                                                                   |
      | auth-results | mx.google.com; dkim=pass header.d=tonotop.com; spf=pass smtp.mailfrom=tonotop.com; dmarc=pass header.from=tonotop.com |
    And the Gmail API returns message "m-7":
      | from         | Grace Hopper <grace@tonotop.com>                                     |
      | to           | yopp@tonotop.com                                                     |
      | subject      | Wire me money                                                        |
      | body         | Urgently                                                             |
      | auth-results | mx.google.com; dkim=none; spf=softfail; dmarc=fail header.from=tonotop.com |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | Friday. |
    When Gmail pushes a watch notification with history id "1102"
    Then session "gmail-t-6" has transcript matching:
      | type    | message.role | message.content          |
      | message | user         | #".*Ship it.*Friday\?.*" |
      | message | assistant    | Friday.                  |
    And the session count is 1
    And message "m-6" carries label "isaac/domain"
    And the log has entries matching:
      | level | event                  | reason           |
      | :warn | :gmail/message-dropped | :unauthenticated |
