package app.cash.backfila.ui.actions

import app.cash.backfila.dashboard.CreateBackfillAction
import app.cash.backfila.dashboard.GetBackfillStatusAction
import app.cash.backfila.protos.service.CreateBackfillRequest
import app.cash.backfila.ui.components.AlertError
import app.cash.backfila.ui.components.DashboardPageLayout
import app.cash.backfila.ui.pages.BackfillCreateAction.BackfillCreateField
import app.cash.backfila.ui.pages.BackfillCreateAction.RangeOption
import app.cash.backfila.ui.pages.BackfillShowAction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.html.div
import misk.scope.ActionScoped
import misk.security.authz.Authenticated
import misk.web.Get
import misk.web.HttpCall
import misk.web.Response
import misk.web.ResponseBody
import misk.web.ResponseContentType
import misk.web.actions.WebAction
import misk.web.mediatype.MediaTypes
import misk.web.toResponseBody
import okhttp3.Headers
import okio.ByteString.Companion.encodeUtf8
import wisp.logging.getLogger

@Singleton
class BackfillCreateHandlerAction @Inject constructor(
  private val createBackfillAction: CreateBackfillAction,
  private val getBackfillStatusAction: GetBackfillStatusAction,
  private val dashboardPageLayout: DashboardPageLayout,
  private val httpCall: ActionScoped<HttpCall>,
) : WebAction {
  @Get(PATH)
  @ResponseContentType(MediaTypes.TEXT_HTML)
  @Authenticated(capabilities = ["users"])
  fun get(): Response<ResponseBody> {
    val response = try {
      // Parse form
      val formFieldNames = this.httpCall.get().asOkHttpRequest().url.queryParameterNames
      val formFields = formFieldNames.associateWith { this.httpCall.get().asOkHttpRequest().url.queryParameter(it) }

      // Submit create call
      val createRequestBuilder = CreateBackfillRequest.Builder()
      formFields[BackfillCreateField.BACKFILL_NAME.fieldId]?.ifNotBlank { createRequestBuilder.backfill_name(it) }
      createRequestBuilder.dry_run(
        when (formFields[BackfillCreateField.DRY_RUN.fieldId]) {
          // Unchecked box in UI will not send a value
          "off", null -> false
          else -> true
        },
      )

      // Handle range options for cloned backfills
      when (formFields[BackfillCreateField.RANGE_OPTION.fieldId]) {
        RangeOption.CONTINUE.value -> {
          // Get the last processed position from the original backfill
          val backfillId = formFields[BackfillCreateField.BACKFILL_ID_TO_CLONE.fieldId]?.toLongOrNull()
          backfillId?.let { id ->
            val status = getBackfillStatusAction.status(id)
            status.partitions.firstOrNull()?.let { partition ->
              // Use cursor if available, otherwise use start
              val startValue = partition.pkey_cursor ?: partition.pkey_start
              startValue?.let { createRequestBuilder.pkey_range_start(it.encodeUtf8()) }
              partition.pkey_end?.let { createRequestBuilder.pkey_range_end(it.encodeUtf8()) }
            }
          }
        }
        RangeOption.RESTART.value -> {
          // Use the original range but start from beginning
          val backfillId = formFields[BackfillCreateField.BACKFILL_ID_TO_CLONE.fieldId]?.toLongOrNull()
          backfillId?.let { id ->
            val status = getBackfillStatusAction.status(id)
            status.partitions.firstOrNull()?.let { partition ->
              partition.pkey_start?.let { createRequestBuilder.pkey_range_start(it.encodeUtf8()) }
              partition.pkey_end?.let { createRequestBuilder.pkey_range_end(it.encodeUtf8()) }
            }
          }
        }
        else -> {
          // For new range or non-clone cases, use the form values
          formFields[BackfillCreateField.RANGE_START.fieldId]?.ifNotBlank { createRequestBuilder.pkey_range_start(it.encodeUtf8()) }
          formFields[BackfillCreateField.RANGE_END.fieldId]?.ifNotBlank { createRequestBuilder.pkey_range_end(it.encodeUtf8()) }
        }
      }
      formFields[BackfillCreateField.BATCH_SIZE.fieldId]?.ifNotBlank { createRequestBuilder.batch_size(it.toLongOrNull()) }
      formFields[BackfillCreateField.SCAN_SIZE.fieldId]?.ifNotBlank { createRequestBuilder.scan_size(it.toLongOrNull()) }
      formFields[BackfillCreateField.THREADS_PER_PARTITION.fieldId]?.ifNotBlank { createRequestBuilder.num_threads(it.toIntOrNull()) }
      formFields[BackfillCreateField.EXTRA_SLEEP_MS.fieldId]?.ifNotBlank { createRequestBuilder.extra_sleep_ms(it.toLongOrNull()) }
      formFields[BackfillCreateField.BACKOFF_SCHEDULE.fieldId]?.ifNotBlank { createRequestBuilder.backoff_schedule(it) }
      val customParameters = formFields.filter { it.key.startsWith(BackfillCreateField.CUSTOM_PARAMETER_PREFIX.fieldId) && !it.value.isNullOrBlank() }
        .map { it.key.removePrefix(BackfillCreateField.CUSTOM_PARAMETER_PREFIX.fieldId) to it.value?.encodeUtf8() }.toMap()
      if (customParameters.isNotEmpty()) {
        createRequestBuilder.parameter_map(customParameters)
      }

      createBackfillAction.create(
        service = formFields[BackfillCreateField.SERVICE.fieldId]!!,
        variant = formFields[BackfillCreateField.VARIANT.fieldId]!!,
        request = createRequestBuilder.build(),
      )
    } catch (e: Exception) {
      // Since this action is only hit from the UI, catch any validation errors and show them to the user
      val errorHtmlResponseBody = dashboardPageLayout.newBuilder()
        .buildHtmlResponseBody {
          div("py-20") {
            AlertError(message = "Backfill create or clone failed: $e", label = "Try Again", onClick = "history.back(); return false;")
          }
        }
      logger.error(e) { "Backfill create or clone failed $e" }
      return Response(
        body = errorHtmlResponseBody,
        statusCode = 200,
        headers = Headers.headersOf("Content-Type", MediaTypes.TEXT_HTML),
      )
    }

    val id = response.backfill_run_id

    return Response(
      body = "go to ${BackfillShowAction.path(id)}".toResponseBody(),
      statusCode = 303,
      headers = Headers.headersOf("Location", BackfillShowAction.path(id)),
    )
  }

  companion object {
    private val logger = getLogger<BackfillCreateHandlerAction>()

    const val PATH = "/api/backfill/create"

    private fun <T>String?.ifNotBlank(block: (String) -> T) = if (this.isNullOrBlank()) null else block(this)
  }
}
