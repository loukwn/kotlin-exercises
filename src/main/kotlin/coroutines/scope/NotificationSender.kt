package coroutines.scope.notificationsender

import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Test
import kotlin.test.assertEquals

class NotificationSender(
    private val client: NotificationClient,
    private val exceptionCollector: ExceptionCollector,
    dispatcher: CoroutineDispatcher,
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        exceptionCollector.collectException(t)
    }
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + exceptionHandler + dispatcher)

    fun sendNotifications(notifications: List<Notification>) {
        notifications.forEach {
            scope.launch {
                client.send(it)
            }
        }
    }

    fun cancel() {
        scope.coroutineContext.cancelChildren()
    }
}

data class Notification(val id: String)

interface NotificationClient {
    suspend fun send(notification: Notification)
}

interface ExceptionCollector {
    fun collectException(throwable: Throwable)
}

class NotificationSenderTest {

    @Test
    fun `should send notifications concurrently`() {
        val fakeNotificationsClient = FakeNotificationClient(delayTime = 200)
        val fakeExceptionCollector = FakeExceptionCollector()
        val testDispatcher = StandardTestDispatcher()
        val sender = NotificationSender(fakeNotificationsClient, fakeExceptionCollector, testDispatcher)
        val notifications = List(20) { Notification("ID$it") }

        // when
        sender.sendNotifications(notifications)
        testDispatcher.scheduler.advanceUntilIdle()

        // then
        assertEquals(notifications, fakeNotificationsClient.sent)
        assertEquals(200, testDispatcher.scheduler.currentTime, "Notifications should be sent concurrently")
    }

    @Test
    fun `should cancel all coroutines when cancel is called`() {
        val fakeNotificationsClient = FakeNotificationClient(delayTime = 1000)
        val fakeExceptionCollector = FakeExceptionCollector()
        val testDispatcher = StandardTestDispatcher()
        val sender = NotificationSender(fakeNotificationsClient, fakeExceptionCollector, testDispatcher)
        val notifications = List(20) { Notification("ID$it") }

        // when
        sender.sendNotifications(notifications)
        testDispatcher.scheduler.advanceTimeBy(500)
        sender.cancel()

        // then
        assert(sender.scope.coroutineContext.job.children.all { it.isCancelled })

        // and scope should still be active
        assert(sender.scope.isActive)
    }

    @Test
    fun `should not cancel other sending processes when one of them fails`() {
        val fakeNotificationsClient = FakeNotificationClient(delayTime = 100, failEvery = 10)
        val fakeExceptionCollector = FakeExceptionCollector()
        val testDispatcher = StandardTestDispatcher()
        val sender = NotificationSender(fakeNotificationsClient, fakeExceptionCollector, testDispatcher)
        val notifications = List(100) { Notification("ID$it") }

        // when
        sender.sendNotifications(notifications)
        testDispatcher.scheduler.advanceUntilIdle()

        // then
        assertEquals(90, fakeNotificationsClient.sent.size)
    }

    @Test
    fun `should collect exceptions from all coroutines`() {
        val fakeNotificationsClient = FakeNotificationClient(delayTime = 100, failEvery = 10)
        val fakeExceptionCollector = FakeExceptionCollector()
        val testDispatcher = StandardTestDispatcher()
        val sender = NotificationSender(fakeNotificationsClient, fakeExceptionCollector, testDispatcher)
        val notifications = List(100) { Notification("ID$it") }

        // when
        sender.sendNotifications(notifications)
        testDispatcher.scheduler.advanceUntilIdle()

        // then
        assertEquals(10, fakeExceptionCollector.collected.size)
    }
}

class FakeNotificationClient(
    val delayTime: Long = 0L,
    val failEvery: Int = Int.MAX_VALUE
) : NotificationClient {
    var sent = emptyList<Notification>()
    var counter = 0
    var usedThreads = emptyList<String>()

    override suspend fun send(notification: Notification) {
        if (delayTime > 0) delay(delayTime)
        usedThreads += Thread.currentThread().name
        counter++
        if (counter % failEvery == 0) {
            throw FakeFailure(notification)
        }
        sent += notification
    }
}

class FakeFailure(val notification: Notification) : Throwable("Planned fail for notification ${notification.id}")

class FakeExceptionCollector : ExceptionCollector {
    var collected = emptyList<Throwable>()

    override fun collectException(throwable: Throwable) = synchronized(this) {
        collected += throwable
    }
}
