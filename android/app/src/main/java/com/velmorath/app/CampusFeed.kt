package com.velmorath.app

data class CampusItem(
    val title: String, val subtitle: String = "", val detail: String = "",
    val id: String = "", val url: String = "", val source: String = "",
    val sourceId: String = "", val publishedAt: String = "", val dueAt: String = "",
    val content: String = "", val course: CourseSlot? = null,
    val startsAt: String = "", val workUrl: String = "",
    val personal: PersonalEvent? = null,
    val submission: Submission = Submission.UNKNOWN, val detailLoaded: Boolean = false,
)
enum class Submission(val label: String) {
    UNKNOWN("状态未知"), NOT_SUBMITTED("未提交"), SUBMITTED("已提交"), COMPLETED("已完成");
}
data class CourseSlot(val day: Int, val from: Int, val to: Int,
    val room: String, val teacher: String, val weeks: String = "")
data class CampusFeed(val items: List<CampusItem>, val caption: String = "",
    val weekStart: String = "", val partial: Boolean = false, val returnedCount: Int = items.size)
enum class CampusSection(val label: String, val hint: String, val emptyText: String) {
    NOTICES("通知", "校园里的新消息", "暂时没有新的通知"),
    MESSAGES("作业", "作业与课程提醒", "暂时没有作业提醒"),
    COURSES("日程", "本周课程安排", "本周没有课程安排"),
    EXAMS("考试", "考试时间与地点", "暂时没有考试安排");
}
data class FeedState(val feed: CampusFeed? = null, val loading: Boolean = false,
    val error: String? = null, val needsLogin: Boolean = false, val updatedAt: Long? = null,
    val detailsLoading: Boolean = false, val calendarRevision: Int = 0)
