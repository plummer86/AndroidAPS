package app.aaps.implementation.stats

import android.content.Context
import android.graphics.Typeface
import android.util.LongSparseArray
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.util.size
import app.aaps.core.data.db.BS
import app.aaps.core.data.db.TDD
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.MidnightTime
import dagger.Reusable
import javax.inject.Inject

@Reusable
class TddCalculatorImpl @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction,
    private val dateUtil: DateUtil,
    private val iobCobCalculator: IobCobCalculator,
    private val persistenceLayer: PersistenceLayer
) : TddCalculator {

    override fun calculate(days: Long, allowMissingDays: Boolean): LongSparseArray<TDD>? {
        var startTime = MidnightTime.calc(dateUtil.now() - T.days(days).msecs())
        val endTime = MidnightTime.calc(dateUtil.now())
        //val stepSize = T.hours(24).msecs() // this is not true on DST change

        val result = LongSparseArray<TDD>()
        // Try to load cached values
        while (startTime < endTime) {
            persistenceLayer.getCalculatedTotalDailyDose(startTime)?.let { result.put(startTime, it) } ?: break
            //startTime += stepSize
            startTime = MidnightTime.calc(startTime + T.hours(27).msecs()) // be sure we find correct midnight
        }

        if (endTime > startTime) {
            var midnight = startTime
            while (midnight < endTime) {
                val tdd = calculate(midnight, midnight + T.hours(24).msecs(), allowMissingData = false)
                if (tdd != null) result.put(midnight, tdd)
                midnight = MidnightTime.calc(midnight + T.hours(27).msecs()) // be sure we find correct midnight
            }
        }
        for (i in 0 until result.size()) {
            val tdd = result.valueAt(i)
            if (tdd.ids.pumpType != PumpType.CACHE) {
                tdd.ids.pumpType = PumpType.CACHE
                aapsLogger.debug(LTag.CORE, "Storing TDD ${tdd.timestamp}")
                persistenceLayer.insertTotalDailyDose(tdd)
            }
        }
        if (result.size.toLong() == days || allowMissingDays) return result
        return null
    }

    override fun calculateToday(): TDD? {
        val startTime = MidnightTime.calc(dateUtil.now())
        val endTime = dateUtil.now()
        return calculate(startTime, endTime, allowMissingData = true)
    }

    override fun calculateDaily(startHours: Long, endHours: Long): TDD? {
        val startTime = dateUtil.now() + T.hours(hour = startHours).msecs()
        val endTime = dateUtil.now() + T.hours(hour = endHours).msecs()
        return calculate(startTime, endTime, allowMissingData = false)
    }

    override fun calculate(startTime: Long, endTime: Long, allowMissingData: Boolean): TDD? {
        val startTimeAligned = startTime - startTime % (5 * 60 * 1000)
        val endTimeAligned = endTime - endTime % (5 * 60 * 1000)
        val tdd = TDD(timestamp = startTimeAligned)
        var tbrFound = false
        persistenceLayer.getBolusesFromTimeToTime(startTime, endTime, true)
            .filter { it.type != BS.Type.PRIMING }
            .forEach { t ->
                tdd.bolusAmount += t.amount
            }
        persistenceLayer.getCarbsFromTimeToTimeExpanded(startTime, endTime, true).forEach { t ->
            tdd.carbs += t.amount
        }
        val calculationStep = T.mins(5).msecs()
        for (t in startTimeAligned until endTimeAligned step calculationStep) {

            val profile = profileFunction.getProfile(t) ?: if (allowMissingData) continue else return null
            val tbr = iobCobCalculator.getBasalData(profile, t)
            if (tbr.isTempBasalRunning) tbrFound = true
            val absoluteRate = tbr.tempBasalAbsolute
            tdd.basalAmount += absoluteRate / 60.0 * 5.0

            if (!activePlugin.activePump.isFakingTempsByExtendedBoluses) {
                val eb = persistenceLayer.getExtendedBolusActiveAt(t)
                val absoluteEbRate = eb?.rate ?: 0.0
                tdd.bolusAmount += absoluteEbRate / 60.0 * 5.0
            }
        }
        tdd.totalAmount = tdd.bolusAmount + tdd.basalAmount
        aapsLogger.debug(LTag.CORE, tdd.toString())
        if (tdd.bolusAmount > 0 || tdd.basalAmount > 0 || tbrFound) return tdd
        return null
    }

    override fun averageTDD(tdds: LongSparseArray<TDD>?): TDD? {
        val totalTdd = TDD(timestamp = dateUtil.now())
        tdds ?: return null
        if (tdds.size() == 0) return null
        for (i in 0 until tdds.size()) {
            val tdd = tdds.valueAt(i)
            totalTdd.basalAmount += tdd.basalAmount
            totalTdd.bolusAmount += tdd.bolusAmount
            totalTdd.totalAmount += tdd.totalAmount
            totalTdd.carbs += tdd.carbs
        }
        totalTdd.basalAmount /= tdds.size().toDouble()
        totalTdd.bolusAmount /= tdds.size().toDouble()
        totalTdd.totalAmount /= tdds.size().toDouble()
        totalTdd.carbs /= tdds.size().toDouble()
        return totalTdd
    }

    override fun stats(context: Context): TableLayout {
        val tdds = calculate(7, allowMissingDays = true) ?: return TableLayout(context)
        val averageTdd = averageTDD(tdds)
        val todayTdd = calculateToday()
        val lp = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT)
        return TableLayout(context).also { layout ->
            layout.layoutParams = TableLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            layout.addView(TextView(context).apply {
                text = rh.gs(app.aaps.core.ui.R.string.tdd)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER_HORIZONTAL
                setTextAppearance(android.R.style.TextAppearance_Material_Medium)
            })
            layout.addView(TDD.toTableRowHeader(context, rh, includeCarbs = true))
            for (i in 0 until tdds.size()) layout.addView(tdds.valueAt(i).toTableRow(context, rh, dateUtil, includeCarbs = true))
            averageTdd?.let { averageTdd ->
                layout.addView(TextView(context).apply {
                    layoutParams = lp
                    text = rh.gs(app.aaps.core.ui.R.string.average)
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER_HORIZONTAL
                    setTextAppearance(android.R.style.TextAppearance_Material_Medium)
                })
                layout.addView(averageTdd.toTableRow(context, rh, tdds.size(), includeCarbs = true))
            }
            todayTdd?.let {
                layout.addView(TextView(context).apply {
                    text = rh.gs(app.aaps.core.interfaces.R.string.today)
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER_HORIZONTAL
                    setTextAppearance(android.R.style.TextAppearance_Material_Medium)
                })
                layout.addView(todayTdd.toTableRow(context, rh, dateUtil, includeCarbs = true))
            }
        }
    }
}
