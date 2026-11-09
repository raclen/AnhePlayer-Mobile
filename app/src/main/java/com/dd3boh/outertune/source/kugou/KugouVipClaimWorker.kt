package com.dd3boh.outertune.source.kugou

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dd3boh.outertune.utils.AppLogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

class KugouVipClaimWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            KugouConcept.initialize(applicationContext)
            val account = withTimeoutOrNull(30_000) {
                KugouConcept.awaitRestored()
                KugouConcept.accountState.first { it.status != KugouAccountStatus.RESTORING }
            } ?: run {
                AppLogManager.w(TAG, "酷狗后台 VIP 领取等待账号恢复超时，第 ${runAttemptCount + 1} 次尝试")
                return if (runAttemptCount < 3) Result.retry() else Result.success()
            }
            if (account.status != KugouAccountStatus.SIGNED_IN || !account.autoClaimVip) return Result.success()
            when (KugouConcept.claimDailyVip()) {
                KugouClaimStatus.ERROR -> if (runAttemptCount < 3) Result.retry() else Result.success()
                else -> Result.success()
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            AppLogManager.e(TAG, "酷狗后台 VIP 领取任务异常：${e.message ?: "未知原因"}", e)
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
    companion object {
        private const val TAG = "KugouVipClaimWorker"
        private const val NAME = "kugou_concept_daily_vip"
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<KugouVipClaimWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    }
}
