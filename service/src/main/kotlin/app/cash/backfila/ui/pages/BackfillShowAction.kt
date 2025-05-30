package app.cash.backfila.ui.pages

import app.cash.backfila.dashboard.EditPartitionCursorAction
import app.cash.backfila.dashboard.GetBackfillStatusAction
import app.cash.backfila.dashboard.GetBackfillStatusResponse
import app.cash.backfila.dashboard.ViewLogsAction
import app.cash.backfila.service.persistence.BackfillState
import app.cash.backfila.ui.actions.BackfillShowButtonHandlerAction
import app.cash.backfila.ui.components.AutoReload
import app.cash.backfila.ui.components.DashboardPageLayout
import app.cash.backfila.ui.components.PageTitle
import app.cash.backfila.ui.components.Pagination
import app.cash.backfila.ui.components.ProgressBar
import app.cash.backfila.ui.pages.BackfillCreateAction.BackfillCreateField.CUSTOM_PARAMETER_PREFIX
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.html.ButtonType
import kotlinx.html.InputType
import kotlinx.html.TagConsumer
import kotlinx.html.ThScope
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.input
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import misk.security.authz.Authenticated
import misk.tailwind.Link
import misk.web.Get
import misk.web.PathParam
import misk.web.QueryParam
import misk.web.Response
import misk.web.ResponseBody
import misk.web.ResponseContentType
import misk.web.actions.WebAction
import misk.web.mediatype.MediaTypes

@Singleton
class BackfillShowAction @Inject constructor(
  private val getBackfillStatusAction: GetBackfillStatusAction,
  private val dashboardPageLayout: DashboardPageLayout,
  private val viewLogsAction: ViewLogsAction,
) : WebAction {
  @Get(PATH)
  @ResponseContentType(MediaTypes.TEXT_HTML)
  @Authenticated(capabilities = ["users"])
  fun get(
    @PathParam id: Long,
    @QueryParam offset: String? = null,
    @QueryParam lastOffset: String? = null,
  ): Response<ResponseBody> {
    val backfill = getBackfillStatusAction.status(id, offset)
    val label =
      if (backfill.variant == "default") backfill.service_name else "${backfill.service_name} (${backfill.variant})"

    val configurationRows = backfill.toConfigurationRows(id)
    val leftColumnConfigurationRows = configurationRows.take(
      configurationRows.size / 2 +
        // Handles odd length of rows, chooses the odd row to be in the left column
        configurationRows.size % 2,
    )
    val rightColumnConfigurationRows = configurationRows.takeLast(configurationRows.size / 2)

    val htmlResponseBody = dashboardPageLayout.newBuilder()
      .title("Backfill $id | Backfila")
      .breadcrumbLinks(
        Link("Services", ServiceIndexAction.PATH),
        Link(
          label,
          ServiceShowAction.path(
            service = backfill.service_name,
            variantOrBlank = if (backfill.variant != "default") backfill.variant else "",
          ),
        ),
        Link("Backfill #$id", path(id)),
      )
      .buildHtmlResponseBody {
        AutoReload {
          PageTitle("${backfill.service_name} Backfill Run", "#$id", backfill.name) {
            a {
              href = BackfillCreateAction.path(
                service = backfill.service_name,
                variantOrBackfillNameOrId = if (backfill.variant != "default") backfill.variant else id.toString(),
                backfillNameOrIdOrBlank = if (backfill.variant != "default") id.toString() else "",
              )

              button(classes = "rounded-full bg-indigo-600 px-3 py-1.5 text-sm font-semibold text-white shadow-sm hover:bg-indigo-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600") {
                type = ButtonType.button
                +"""Clone"""
              }
            }
          }

          Card {
            div("mx-auto grid max-w-2xl grid-cols-1 grid-rows-1 items-start gap-x-24 gap-y-8 lg:mx-0 lg:max-w-none lg:grid-cols-2") {
              // <!-- Left Column -->"""
              div("") {
                h2("text-base font-semibold leading-6 text-gray-900") { +"""Configuration""" }
                dl("divide-y divide-gray-100") {
                  leftColumnConfigurationRows.map {
                    ConfigurationRows(id, it)
                  }
                }
              }

              // <!-- Right Column -->"""
              div("divide-x divide-gray-100") {
                dl("divide-y divide-gray-100") {
                  rightColumnConfigurationRows.map {
                    ConfigurationRows(id, it)
                  }
                }
              }
            }
          }

          Card {
            // Partitions
            h2("text-base font-semibold leading-6 text-gray-900") { +"""Partitions""" }
            table("my-8 whitespace-nowrap text-left text-sm leading-6") {
              thead("border-b border-gray-200 text-gray-900") {
                tr {
                  th(classes = "px-0 py-3 font-semibold") {
                    scope = ThScope.col
                    +"""Name"""
                  }
                  th(classes = "hidden py-3 pl-8 pr-0 text-right font-semibold sm:table-cell") {
                    scope = ThScope.col
                    +"""State"""
                  }
                  th(classes = "hidden py-3 pl-8 pr-0 text-right font-semibold sm:table-cell") {
                    scope = ThScope.col
                    +"""Cursor"""
                  }
                  th(classes = "py-3 pl-8 pr-0 text-right font-semibold") {
                    scope = ThScope.col
                    +"""Range"""
                  }
                  th(classes = "py-3 pl-8 pr-0 text-right font-semibold") {
                    scope = ThScope.col
                    +"""Progress"""
                  }
                  th(classes = "py-3 pl-8 pr-0 text-right font-semibold") {
                    scope = ThScope.col
                    +"""Progress (%)"""
                  }
                  th(classes = "py-3 pl-8 pr-0 text-right font-semibold") {
                    scope = ThScope.col
                    +"""Rate"""
                  }
                  th(classes = "py-3 pl-8 pr-0 text-right font-semibold") {
                    scope = ThScope.col
                    +"""ETA"""
                  }
                  if (backfill.state == BackfillState.PAUSED) {
                    th(classes = "py-3 pl-8 pr-0 text-right font-semibold") {
                      scope = ThScope.col
                      +"""Actions"""
                    }
                  }
                }
              }
              tbody {
                backfill.partitions.map { partition ->
                  tr("border-b border-gray-100") {
                    td("max-w-[50%] px-0 py-5 align-top") {
                      div("truncate font-medium text-gray-900") { +partition.name }
                    }
                    td("hidden py-5 pl-8 pr-0 text-right align-top text-gray-700 sm:table-cell") { +partition.state.name }
                    td("hidden py-5 pl-8 pr-0 text-right align-top tabular-nums text-gray-700 sm:table-cell") {
                      +(partition.pkey_cursor ?: "")
                    }
                    td("hidden py-5 pl-8 pr-0 text-right align-top text-gray-700 sm:table-cell") { +"""${partition.pkey_start} to ${partition.pkey_end}""" }
                    td("hidden py-5 pl-8 pr-0 text-right align-top text-gray-700 sm:table-cell") { +"""${partition.backfilled_matching_record_count} / ${partition.computed_matching_record_count}""" }
                    td("hidden py-5 pl-8 pr-0 text-right align-top tabular-nums text-gray-700 sm:table-cell") {
                      ProgressBar(
                        partition.backfilled_matching_record_count,
                        partition.computed_matching_record_count,
                        partition.precomputing_done,
                      )
                    }
                    td("hidden py-5 pl-8 pr-0 text-right align-top tabular-nums text-gray-700 sm:table-cell") {
                      when {
                        partition.state != BackfillState.RUNNING -> +"-"
                        partition.matching_records_per_minute == null ||
                          partition.matching_records_per_minute <= 0 -> {
                          if (!partition.precomputing_done) { +"Computing..." } else { +"Calculating..." }
                        }
                        else -> +"""${partition.matching_records_per_minute} #/m"""
                      }
                    }
                    td("py-5 pl-8 pr-0 text-right align-top tabular-nums text-gray-700") {
                      when {
                        partition.state != BackfillState.RUNNING -> +"-"
                        !partition.precomputing_done -> +"Computing..."
                        partition.matching_records_per_minute == null || partition.matching_records_per_minute <= 0 ||
                          partition.computed_matching_record_count <= 0 -> +"Calculating..."
                        else -> {
                          val etaSeconds = (partition.computed_matching_record_count - partition.backfilled_matching_record_count).toDouble() / (partition.matching_records_per_minute / 60.0)
                          +formatEta(etaSeconds * 1000)
                        }
                      }
                    }
                    if (backfill.state == BackfillState.PAUSED) {
                      td("py-5 pl-8 pr-0 text-right align-top") {
                        a(
                          href = EditPartitionCursorAction.path(id, partition.name),
                          classes = "text-indigo-600 hover:text-indigo-900",
                        ) {
                          +"Edit Cursor"
                        }
                      }
                    }
                  }
                }
              }
            }
          }

          Card {
            // Events
            div {
              h2("text-base font-semibold leading-6 text-gray-900") { +"""Events""" }
              table("my-8 text-left text-sm leading-6") {
                thead("border-b border-gray-200 text-gray-900") {
                  tr {
                    th(classes = "px-0 py-3 font-semibold") {
                      scope = ThScope.col
                      +"""Time"""
                    }
                    th(classes = "hidden py-3 pl-8 pr-0 font-semibold sm:table-cell") {
                      scope = ThScope.col
                      +"""User"""
                    }
                    th(classes = "hidden py-3 pl-8 pr-0 font-semibold sm:table-cell") {
                      scope = ThScope.col
                      +"""Partition"""
                    }
                    th(classes = "py-3 pl-8 pr-0 font-semibold") {
                      scope = ThScope.col
                      +"""Event"""
                    }
                    th(classes = "py-3 pl-8 pr-0 font-semibold") {
                      scope = ThScope.col
                      +"""More Data"""
                    }
                  }
                }
                tbody {
                  backfill.event_logs.map { log ->
                    tr("border-b border-gray-100") {
                      td("hidden py-5 pl-8 pr-0 align-top text-wrap text-gray-700 sm:table-cell") {
                        +log.occurred_at.toString().replace("T", " ").dropLast(5)
                      }
                      td("hidden py-5 pl-8 pr-0 align-top text-gray-700 sm:table-cell") { log.user?.let { +it } }
                      td("hidden py-5 pl-8 pr-0 align-top text-gray-700 sm:table-cell") { log.partition_name?.let { +it } }
                      td("hidden py-5 pl-8 pr-0 align-top max-w-2 text-wrap text-gray-700 sm:table-cell") { +log.message }
                      td("hidden py-5 pl-8 pr-0 align-top max-w-2 text-wrap text-gray-700 sm:table-cell") { log.extra_data?.let { +it } }
                    }
                  }
                }
              }

              Pagination(backfill.next_offset, offset, lastOffset, path(id))
            }
          }
        }
      }

    return Response(htmlResponseBody)
  }

  data class DescriptionListRow(
    val label: String,
    val description: String,
    /* Value of the button click is provided through the button.href field. */
    val button: Link? = null,
    val updateFieldId: String? = null,
    val cancelButton: Link? = null,
    val deleteButton: Link? = null,
  )

  private fun getStateButton(state: BackfillState): Link? {
    return when (state) {
      BackfillState.PAUSED -> Link(
        label = START_STATE_BUTTON_LABEL,
        href = BackfillState.RUNNING.name,
      )
      // COMPLETE and CANCELLED represent final states.
      BackfillState.COMPLETE -> null
      BackfillState.CANCELLED -> null
      else -> Link(
        label = PAUSE_STATE_BUTTON_LABEL,
        href = BackfillState.PAUSED.name,
      )
    }
  }

  private fun getCancelButton(state: BackfillState): Link? {
    return when (state) {
      BackfillState.PAUSED -> Link(
        label = CANCEL_STATE_BUTTON_LABEL,
        href = BackfillState.CANCELLED.name,
      )
      else -> null
    }
  }

  private fun getDeleteButton(state: BackfillState, deletedAt: Instant?): Link? {
    if (deletedAt != null) {
      return null
    }
    return when (state) {
      BackfillState.COMPLETE, BackfillState.CANCELLED -> Link(
        label = DELETE_STATE_BUTTON_LABEL,
        href = "soft_delete",
      )
      else -> null
    }
  }

  private fun GetBackfillStatusResponse.toConfigurationRows(id: Long) = listOf(
    DescriptionListRow(
      label = "State",
      description = state.name,
      button = getStateButton(state),
      updateFieldId = "state",
      cancelButton = getCancelButton(state),
      deleteButton = getDeleteButton(state, deleted_at),
    ),
    DescriptionListRow(
      label = "Dry Run",
      description = if (dry_run) "dry run" else "wet run",
    ),
    DescriptionListRow(
      label = "Threads per partition",
      description = num_threads.toString(),
      button = Link(
        label = UPDATE_BUTTON_LABEL,
        href = "#",
      ),
      updateFieldId = "num_threads",
    ),
    DescriptionListRow(
      label = "Scan Size",
      description = scan_size.toString(),
      button = Link(
        label = UPDATE_BUTTON_LABEL,
        href = "#",
      ),
      updateFieldId = "scan_size",
    ),
    DescriptionListRow(
      label = "Batch Size",
      description = batch_size.toString(),
      button = Link(
        label = UPDATE_BUTTON_LABEL,
        href = "#",
      ),
      updateFieldId = "batch_size",
    ),
    DescriptionListRow(
      label = "Sleep betweeen batches (ms)",
      description = extra_sleep_ms.toString(),
      button = Link(
        label = UPDATE_BUTTON_LABEL,
        href = "#",
      ),
      updateFieldId = "extra_sleep_ms",
    ),
    DescriptionListRow(
      label = "Backoff Schedule",
      description = backoff_schedule ?: "",
      button = Link(
        label = UPDATE_BUTTON_LABEL,
        href = "#",
      ),
      updateFieldId = "backoff_schedule",
    ),
    DescriptionListRow(
      label = "Created",
      description = "$created_at by $created_by_user",
    ),
    DescriptionListRow(
      label = "Logs",
      description = "",
      button = Link(
        label = VIEW_LOGS_BUTTON_LABEL,
        href = viewLogsAction.getUrl(id),
      ),
    ),
  ) + if (parameters?.isNotEmpty() == true) {
    listOf(
      DescriptionListRow(
        label = "Custom Parameters",
        description = "",
      ),
    ) +
      parameters.map { (key, value) ->
        DescriptionListRow(
          label = key.removePrefix(CUSTOM_PARAMETER_PREFIX.fieldId),
          description = value,
        )
      }
  } else {
    listOf()
  }

  private fun TagConsumer<*>.Card(block: TagConsumer<*>.() -> Unit) {
    div("-mx-4 mb-8 px-4 py-8 overflow-x-auto shadow-sm ring-1 ring-gray-900/5 sm:mx-0 sm:rounded-lg sm:px-8 lg:col-span-2 lg:row-span-2 lg:row-end-2") {
      block()
    }
  }

  private fun TagConsumer<*>.ConfigurationRows(id: Long, it: DescriptionListRow) {
    div("px-4 py-6 sm:grid sm:grid-cols-3 sm:gap-4 sm:px-0") {
      attributes["data-controller"] = "toggle"

      dt("text-sm font-medium leading-6 text-gray-900") { +it.label }
      dd("mt-1 flex text-sm leading-6 text-gray-700 sm:col-span-2 sm:mt-0") {
        span("flex-grow") {
          attributes["data-toggle-target"] = "toggleable"
          attributes["data-css-class"] = "hidden"

          +it.description
        }
        it.button?.let { button ->
          if (button.label == UPDATE_BUTTON_LABEL) {
            // Initial Update Button to toggle showing form
            span("ml-4 flex-shrink-0") {
              attributes["data-toggle-target"] = "toggleable"
              attributes["data-css-class"] = "hidden"

              button(
                classes = "mt-1 rounded-md font-medium text-indigo-600 hover:text-indigo-500",
              ) {
                attributes["data-action"] = "toggle#toggle"
                type = ButtonType.button
                +button.label
              }
            }

            // Have initial click reveal the update form with editable input
            form(classes = "flex-grow hidden") {
              attributes["data-toggle-target"] = "toggleable"
              attributes["data-css-class"] = "hidden"

              action = BackfillShowButtonHandlerAction.path(id)

              it.updateFieldId?.let { updateFieldId ->
                input {
                  type = InputType.hidden
                  name = "field_id"
                  value = updateFieldId
                }

                div {
                  div("flex rounded-md shadow-sm") {
                    div("relative flex flex-grow items-stretch focus-within:z-10") {
                      input(classes = "block w-full rounded-none rounded-l-md border-0 py-1.5 text-gray-900 ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6") {
                        name = "field_value"
                        value = it.description
                      }
                    }
                    button(classes = "relative -ml-px inline-flex items-center gap-x-1.5 rounded-r-md px-3 py-2 text-sm font-semibold text-gray-900 ring-1 ring-inset ring-gray-300 hover:bg-gray-50") {
                      type = ButtonType.submit
                      +"""Update"""
                    }
                  }
                }
              }
            }

            // Cancel Button to hide form
            span("hidden ml-4 flex-shrink-0") {
              attributes["data-toggle-target"] = "toggleable"
              attributes["data-css-class"] = "hidden"

              button(
                classes = "mt-1 rounded-md font-medium text-indigo-600 hover:text-indigo-500",
              ) {
                attributes["data-action"] = "toggle#toggle"
                type = ButtonType.button
                +"Cancel"
              }
            }
          } else if (button.label == VIEW_LOGS_BUTTON_LABEL) {
            span("ml-4 flex-shrink-0") {
              // View logs button will link to external logs provider
              a {
                href = button.href
                target = "_blank"

                button(
                  classes = "rounded-md font-medium text-indigo-600 hover:text-indigo-500",
                ) {
                  type = ButtonType.submit
                  +button.label
                }
              }
            }
          } else {
            span("ml-4 flex-shrink-0") {
              // Button when clicked updates without additional form
              form {
                action = BackfillShowButtonHandlerAction.path(id)

                it.updateFieldId?.let {
                  input {
                    type = InputType.hidden
                    name = "field_id"
                    value = it
                  }

                  input {
                    type = InputType.hidden
                    name = "field_value"
                    value = button.href
                  }
                }

                val buttonStyle = if (it.updateFieldId == "state") {
                  val color = if (it.button.label == START_STATE_BUTTON_LABEL) "green" else "yellow"
                  "rounded-full bg-$color-600 px-3 py-1.5 text-sm font-semibold text-white shadow-sm hover:bg-$color-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-$color-600"
                } else {
                  "rounded-md font-medium text-indigo-600 hover:text-indigo-500"
                }
                button(
                  classes = buttonStyle,
                ) {
                  type = ButtonType.submit
                  +button.label
                }
              }

              // Add cancel button if present
              it.cancelButton?.let { cancelButton ->
                span("ml-2") {
                  form {
                    action = BackfillShowButtonHandlerAction.path(id)

                    it.updateFieldId?.let {
                      input {
                        type = InputType.hidden
                        name = "field_id"
                        value = it
                      }

                      input {
                        type = InputType.hidden
                        name = "field_value"
                        value = cancelButton.href
                      }
                    }

                    button(
                      classes = "rounded-full bg-red-600 px-3 py-1.5 text-sm font-semibold text-white shadow-sm hover:bg-red-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-red-600",
                    ) {
                      type = ButtonType.submit
                      +cancelButton.label
                    }
                  }
                }
              }
            }
          }
        }

        it.deleteButton?.let { deleteButton ->
          span("ml-2") {
            form {
              action = BackfillShowButtonHandlerAction.path(id)

              it.updateFieldId?.let {
                input {
                  type = InputType.hidden
                  name = "field_id"
                  value = it
                }

                input {
                  type = InputType.hidden
                  name = "field_value"
                  value = deleteButton.href
                }
              }

              button(
                classes = "rounded-full bg-gray-600 px-3 py-1.5 text-sm font-semibold text-white shadow-sm hover:bg-gray-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-gray-600",
              ) {
                type = ButtonType.submit
                +deleteButton.label
              }
            }
          }
        }
      }
    }
  }

  private fun formatEta(etaMillis: Double): String {
    val durationSeconds = etaMillis / 1000
    var temp = durationSeconds.toLong()
    val sb = StringBuilder()

    val years = temp / 31536000
    if (years > 0) {
      sb.append("${years}y")
      temp %= 31536000
    }

    val days = temp / 86400
    if (days > 0) {
      sb.append("${days}d")
      temp %= 86400
    }

    val hours = temp / 3600
    if (hours > 0) {
      sb.append("${hours}h")
      temp %= 3600
    }

    val minutes = temp / 60
    if (minutes > 0) {
      sb.append("${minutes}m")
      temp %= 60
    }

    val seconds = temp
    if (seconds > 0) {
      sb.append("${seconds}s")
    }

    return if (sb.isEmpty()) "< 1s" else sb.toString()
  }

  companion object {
    private const val PATH = "/backfills/{id}"
    fun path(id: String) = PATH.replace("{id}", id)
    fun path(id: Long) = path(id.toString())

    const val START_STATE_BUTTON_LABEL = "Start"
    const val PAUSE_STATE_BUTTON_LABEL = "Pause"
    const val CANCEL_STATE_BUTTON_LABEL = "Cancel"
    const val DELETE_STATE_BUTTON_LABEL = "Delete"
    const val UPDATE_BUTTON_LABEL = "Update"
    const val VIEW_LOGS_BUTTON_LABEL = "View Logs"
  }
}
