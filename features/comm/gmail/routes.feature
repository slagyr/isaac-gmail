Feature: Gmail routes and verdict labels
  Every gated INBOX message is matched against gmail-routes, an ordered table
  of named routes (one .edn file per route, ordered by :order; ties by name).
  Routes are the whitelist: a :converse or :task route must name :match :from,
  and a *@domain pattern only admits a sender Gmail authenticated. The first
  matching route wins. Every gated message — routed, ignored, or unrouted —
  gets a Gmail label isaac/<route-name> (or isaac/ignored, isaac/unrouted,
  isaac/default with no routes configured) before any turn starts, so the
  label is both an audit trail and the idempotency check that lets two hosts
  (push + pull) share one inbox safely. Bean: isaac-sb6d.

  Background:
    Given default Grover setup in "/test/gmail-routes"
    And config:
      | log.output                | memory           |
      | google.tonotop.project    | marigold         |
      | comms.gmail.gmail/account | yopp@tonotop.com |
      | comms.gmail.gmail/crew    | main             |
      | sessions.naming-strategy  | sequential       |
    And the google auth store has access "at-1" and refresh "rt-1"
    And the gmail history cursor is "1000"

  @wip
  Scenario: a route matching the delivery address starts a turn on that route's crew
    Given config:
      | gmail-routes.ops.order      | 10            |
      | gmail-routes.ops.match.to   | yopp+ops@*    |
      | gmail-routes.ops.match.from | *@tonotop.com |
      | gmail-routes.ops.action     | converse      |
      | gmail-routes.ops.crew       | ops           |
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from    | ada@tonotop.com      |
      | to      | yopp+ops@tonotop.com |
      | subject | Prod incident        |
      | body    | Deploy is down       |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | On it.  |
    When Gmail pushes a watch notification with history id "1042"
    Then message "m-1" carries label "isaac/ops"
    And session "gmail-t-1" has transcript matching:
      | type    | message.role | message.crew | message.content     |
      | message | user         | ops          | #".*Deploy is down.*" |
      | message | assistant    | ops          | On it.               |

  @wip
  Scenario: the first matching route wins over a later, broader route
    Given config:
      | gmail-routes.ops.order       | 10            |
      | gmail-routes.ops.match.to    | yopp+ops@*    |
      | gmail-routes.ops.match.from  | *@tonotop.com |
      | gmail-routes.ops.action      | converse      |
      | gmail-routes.ops.crew        | ops           |
      | gmail-routes.team.order      | 90            |
      | gmail-routes.team.match.from | *@tonotop.com |
      | gmail-routes.team.action     | converse      |
      | gmail-routes.team.crew       | main          |
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from    | ada@tonotop.com      |
      | to      | yopp+ops@tonotop.com |
      | subject | Prod incident        |
      | body    | Deploy is down       |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | On it.  |
    When Gmail pushes a watch notification with history id "1042"
    Then message "m-1" carries label "isaac/ops"
    And message "m-1" does not carry label "isaac/team"

  @wip
  Scenario: an ignore route labels the message and marks it read, unless configured not to
    Given config:
      | gmail-routes.newsletters.order      | 30             |
      | gmail-routes.newsletters.match.from | *@substack.com |
      | gmail-routes.newsletters.action     | ignore         |
    And message "m-1" already carries label "UNREAD"
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from    | digest@substack.com |
      | to      | yopp@tonotop.com    |
      | subject | This week's reads   |
      | body    | Top 5 links         |
    When Gmail pushes a watch notification with history id "1042"
    Then message "m-1" carries label "isaac/newsletters"
    And message "m-1" does not carry label "UNREAD"
    And the session count is 0

    Given config:
      | comms.gmail.gmail/ignore-marks-read | false |
    And message "m-2" already carries label "UNREAD"
    And the Gmail API history since "1042" adds messages:
      | id  | threadId |
      | m-2 | t-2      |
    And the Gmail API returns message "m-2":
      | from    | digest@substack.com |
      | to      | yopp@tonotop.com    |
      | subject | Next week's reads   |
      | body    | Top 5 links again   |
    When Gmail pushes a watch notification with history id "1099"
    Then message "m-2" carries label "isaac/newsletters"
    And message "m-2" carries label "UNREAD"

  @wip
  Scenario: a message matching no route is labelled unrouted and logged once, no turn
    Given the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from    | ada@tonotop.com    |
      | to      | yopp@tonotop.com   |
      | subject | Random thought     |
      | body    | Did you see this?  |
    When Gmail pushes a watch notification with history id "1042"
    Then message "m-1" carries label "isaac/unrouted"
    And the session count is 0
    And the log has entries matching:
      | level | event           | from            | subject        |
      | :info | :gmail/unrouted | ada@tonotop.com | Random thought |

  @wip
  Scenario: bulk precedence overrides a matching converse route
    Given config:
      | gmail-routes.team.order      | 90            |
      | gmail-routes.team.match.from | *@tonotop.com |
      | gmail-routes.team.action     | converse      |
      | gmail-routes.team.crew       | main          |
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from       | ada@tonotop.com  |
      | to         | yopp@tonotop.com |
      | subject    | Weekly digest    |
      | body       | Team update      |
      | precedence | bulk             |
    When Gmail pushes a watch notification with history id "1042"
    Then message "m-1" carries label "isaac/ignored"
    And the session count is 0

  @wip
  Scenario: a message already labelled by an earlier pass is skipped
    Given config:
      | gmail-routes.team.order      | 90            |
      | gmail-routes.team.match.from | *@tonotop.com |
      | gmail-routes.team.action     | converse      |
      | gmail-routes.team.crew       | main          |
    And message "m-1" already carries label "isaac/team"
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from    | ada@tonotop.com  |
      | to      | yopp@tonotop.com |
      | subject | Standup          |
      | body    | On my way        |
    When Gmail pushes a watch notification with history id "1042"
    Then the session count is 0
    And the log has entries matching:
      | level  | event                 | id  |
      | :debug | :gmail/already-routed | m-1 |

  @wip
  Scenario: a missing label is created once and reused for the next message
    Given config:
      | gmail-routes.ops.order      | 10            |
      | gmail-routes.ops.match.to   | yopp+ops@*    |
      | gmail-routes.ops.match.from | *@tonotop.com |
      | gmail-routes.ops.action     | converse      |
      | gmail-routes.ops.crew       | ops           |
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
      | m-2 | t-2      |
    And the Gmail API returns message "m-1":
      | from    | ada@tonotop.com      |
      | to      | yopp+ops@tonotop.com |
      | subject | First incident       |
      | body    | Ship it              |
    And the Gmail API returns message "m-2":
      | from    | ada@tonotop.com      |
      | to      | yopp+ops@tonotop.com |
      | subject | Second incident      |
      | body    | Ship it again        |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | On it.  |
      | model | type | content |
      | echo  | text | On it.  |
    When Gmail pushes a watch notification with history id "1099"
    Then the Gmail API created label "isaac/ops" 1 times

  @wip
  Scenario: no routes configured behaves as before, but still labels the default route
    Given the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from    | ada@tonotop.com     |
      | to      | yopp@tonotop.com    |
      | subject | Deploy window       |
      | body    | Can we ship Friday? |
    And the following model responses are queued:
      | model | type | content       |
      | echo  | text | Friday works. |
    When Gmail pushes a watch notification with history id "1042"
    Then session "gmail-t-1" has transcript matching:
      | type    | message.role | message.content                                    |
      | message | user         | #".*ada@tonotop.com.*Deploy window.*ship Friday.*" |
      | message | assistant    | Friday works.                                      |
    And message "m-1" carries label "isaac/default"

  @wip
  Scenario: a route may live in its own config file
    Given config file "gmail-routes/ops.edn" containing:
      """
      {:order 10 :match {:to "yopp+ops@*" :from "*@tonotop.com"} :action :converse :crew "ops"}
      """
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from    | ada@tonotop.com      |
      | to      | yopp+ops@tonotop.com |
      | subject | Prod incident        |
      | body    | Deploy is down       |
    When Gmail pushes a watch notification with history id "1042"
    Then message "m-1" carries label "isaac/ops"

  @wip
  Scenario: two route files order by :order, not filename
    Given config file "gmail-routes/aaa-catchall.edn" containing:
      """
      {:order 90 :match {:from "*@tonotop.com"} :action :converse :crew "main"}
      """
    And config file "gmail-routes/zzz-ops.edn" containing:
      """
      {:order 10 :match {:to "yopp+ops@*" :from "*@tonotop.com"} :action :converse :crew "ops"}
      """
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from    | ada@tonotop.com      |
      | to      | yopp+ops@tonotop.com |
      | subject | Prod incident        |
      | body    | Deploy is down       |
    When Gmail pushes a watch notification with history id "1042"
    Then message "m-1" carries label "isaac/zzz-ops"
    And message "m-1" does not carry label "isaac/aaa-catchall"

  @wip
  Scenario: adding a route file while running is picked up on the next message, no restart
    Given config file "gmail-routes/team.edn" containing:
      """
      {:order 90 :match {:from "*@tonotop.com"} :action :converse :crew "main"}
      """
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from    | digest@substack.com |
      | to      | yopp@tonotop.com    |
      | subject | This week's reads   |
      | body    | Top 5 links         |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | Noted.  |
    When Gmail pushes a watch notification with history id "1042"
    Then session "gmail-t-1" has transcript matching:
      | type    | message.role | message.content  |
      | message | user         | #".*This week.*" |
      | message | assistant    | Noted.            |

    Given config file "gmail-routes/newsletters.edn" containing:
      """
      {:order 30 :match {:from "*@substack.com"} :action :ignore}
      """
    And the config is reloaded
    And the Gmail API history since "1042" adds messages:
      | id  | threadId |
      | m-2 | t-2      |
    And the Gmail API returns message "m-2":
      | from    | digest@substack.com |
      | to      | yopp@tonotop.com    |
      | subject | Next week's reads   |
      | body    | Top 5 links again   |
    When Gmail pushes a watch notification with history id "1099"
    Then message "m-2" carries label "isaac/newsletters"
    And the session count is 1

  @wip
  Scenario: an unknown route action is reported by config validation, naming the route
    Given config file "gmail-routes/broken.edn" containing:
      """
      {:order 10 :match {:from "*@tonotop.com"} :action :archive}
      """
    Then isaac config validate reports an unknown action for route "broken"
