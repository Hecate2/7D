# 七日 D7 · 架构规划

本文是动工前的架构约定，用于指导实现与自查。需求原文见根目录 `AGENTS.md`，面向用户的原理与操作说明见 `README.md`，界面草图见 `img/` 目录（`img/archive` 为旧稿，不参与实现）。凡本文与草图冲突之处，以 `AGENTS.md` 与 `README.md` 文字为准。

## 1. 产品目标与边界

七日是一个离线、小巧的安卓应用，帮助买房人在看房现场用手机相机测量南侧（或南半球时的北侧）建筑的顶部轮廓，把轮廓拐点记录为若干组（仰角，方位角），再由这些点连成的天际线推算站在测量位置上全年每一天的直射日照时长。它的精度定位是「判断有没有两小时够用，打官司不够」，不替代官方日照分析报告。

明确不做的事：不请求网络权限（数据全部本地）、不做测距（楼多高多远不影响结果）、不建账号不做云同步、视频不参与计算（照片只是瞄准过程的存档与 EXIF 载体）、不强制横竖屏（采集页例外：锁竖屏保住取景面积，分屏时系统忽略方向请求、不影响分屏运行）。

## 2. 工程结构

采用两个 Gradle 模块的单应用工程，语言为 Kotlin，界面用经典 View 体系（XML + 自定义 Canvas 视图），不引入 Compose。原因有三：界面核心是相机取景器上的自绘叠加层与图表，Canvas 绘制最直接；经典 View 构建链路短、产物小，符合「非常小巧」的目标；不引入 Compose 编译插件可显著降低构建复杂度与依赖体积。

`:core` 是一个纯 Kotlin JVM 库，不含任何 Android API，承载全部天文与几何算法：太阳位置、天际线求值、日照时长统计、覆盖度计算。把它与 Android 隔离是为了能在普通 JVM 上跑单元测试——这是整个应用唯一有严格正确性要求的部位，传感器与相机只是喂数据。

`:app` 是 Android 应用，包含传感器管线、相机管线、存储、四个界面与自绘视图，依赖 `:core`。

包结构（`:app` 内）约定：`sensor`（姿态与定位）、`camera`（CameraX 封装与照片落盘）、`data`（数据模型与仓库）、`export`（CSV 与参考图导出）、`ui.groups` / `ui.capture` / `ui.result`（三个页面）、`view`（自绘控件：取景器叠加层、时间线、全年曲线、覆盖条）、`util`。

## 3. 坐标与角度约定

全文统一：方位角从正北起顺时针 0 到 360 度（北 0、东 90、南 180、西 270）；仰角地平线 0 度、天顶 90 度、向下为负。世界坐标系用 ENU（东-北-天）：x 向东、y 向北、z 向上。

手机上另有两套坐标系。设备坐标系：x 指向屏幕右侧、y 指向手机顶部、z 垂直屏幕向外（指向用户）。Android 旋转矢量传感器给出的旋转矩阵 R 把设备坐标系映射到世界坐标系。关键推论：后摄视轴方向为设备 -z 轴，在世界上表示为世界向量 `cam = -(R[2], R[5], R[8])`（R 按行主序展开），由此可一次性算出后摄视轴的方位角与仰角，且在竖持、横持下都成立，不依赖设备朝向假设。这正是瞄准十字线所对应的方向，也是记录拍摄点所用的方向。

界面上的 `+180°` 药丸保留为手动修正开关：默认填充（视为已叠加），此时读数即后摄视轴方向；点按取消后读数加 180 度（即机身上缘朝向），用于外接镜头或发现指向反向的罕见情形。两种状态恰好相差 180 度，与需求文字一致。

拍摄顺序约定为「从一端扫到另一端」：北半球面向南时从右（西，方位角大）向左（东，方位角小）依次拍，这正是相册中从右到左由旧到新的顺序；南半球面向北时镜像，从右（东）向左（西）。方位角在数据上以 0 到 360 连续存储，跨 0 度的环绕（南半球常见）在求值时以「两角间小于 180 度的短弧」处理。

## 4. 天文算法

太阳位置采用 NOAA 简化公式（Julian 世纪 → 几何平黄经、平近点角、中心差、视黄经、黄赤交角、赤纬、均时差 → 时角 → 高度角与方位角），实现于 `:core` 的 `Solar` 对象。公式取几何高度角，并另提供大气折射修正（NOAA 分段近似）；遮挡判定使用几何高度角，日出日落与「太阳在地平线上」使用几何高度角 -0.833 度阈值（等效上缘+折射），从而与常识中的日出日落表一致。与高精度星历的偏差小于 0.02 度，远小于传感器误差，不是瓶颈。**但结果页与采集页的评估走的是 `Solar.DayTrack`**：赤纬与均时差按当日中点各算一次，全天 1440 次采样、全年曲线 365 × 1440 次都复用（否则全年要多算四百多万次三角函数，实测 149.5 → 111 毫秒）。代价是位置相对逐次重算最多差 0.23 度（方位）与 0.15 度（仰角）——比公式本身那 0.02 度大一个量级，但仍远小于罗盘那 2~4 度；若改成按日初取，这两个数会翻倍（0.57 / 0.28 度），所以取中点。

时间一律先换算为 UTC 瞬时再算太阳位置，输出时再换算到组内记录的时区（`java.time`，minSdk 26 自带，无需脱糖）。均时差自动包含在公式里，所以乌鲁木齐的日照会正确落在北京时间 14 点附近，不会错算到正午。

关键日期不写死天文事件，而是取固定日期：冬至 12 月 21 日（南半球取 6 月 21 日，即当地一年中太阳最低的一天）、夏至对称取 6 月 21 日（南半球 12 月 21 日）、春分 3 月 21 日、秋分 9 月 23 日、大寒 1 月 20 日。取景器里的参考弧按赤纬直接算：春分秋分弧即赤纬 0 度弧，今日弧按当天赤纬。固定日期与真值最多差一两天，对「看最凹处」的用途无影响。

## 5. 天际线模型

一组数据存两个有序点列：外部建筑列 `external[{az, el, gapAfter}]` 与天花板列 `ceiling[{az, el}]`。点序即拍摄顺序，两列各自独立编号（界面显示为「外1、外2」「天1、天2」）。`gapAfter` 只存在于外部列，表示该点与其后一个点（拍摄序的下一点）之间是「长按产生的经地平线推断段」。

每一对相邻点构成一个线段，线段有两种模式。直接连线模式下，线段内任一方位角处的遮挡仰角由两端点仰角按方位角线性插值。经地平线模式即长按产生的空隙：从上一端点垂直降到地平线、沿地平线走到下一端点所在方位角、再垂直升到下一端点，因此两端点方位角之间（开区间）遮挡仰角为 0，两端点处仍取端点自身。这一展开与需求中「上点竖直降到地平线、沿地平线走到新点方向、再竖直升到新点」的三段推断一致。

任一查询方位角的遮挡仰角取所有覆盖该方位角的线段候选取最大值（遮挡取高者）。这一条同时解决三类问题：换向回拍造成的方位重叠、外部列内部自交、未来可能的一区多段。因此求值不假设方位角单调，环绕也不特殊处理——「覆盖」的判定用短弧包含关系：设线段两端方位差 d 取到 (-180, 180]，查询点相对起点的差 t 同法归一，d 与 t 同号且 |t| ≤ |d| 即视为落在段内。

未覆盖语义按需求区分：外部列未拍到的方位视为开阔，天花板列未拍到的方位视为全遮挡。实现上求值接口返回的是「天际线边缘仰角」，未覆盖方位一律返回 -∞，由两种判定方向自然分化：外部区判定「太阳高于边缘」，-∞ 等价于永远高于、即开阔；天花板区判定「太阳低于边缘」，-∞ 等价于永远不满足、即全遮挡。点列为空即「全周未拍到」，按同一规则退化：外部列空则全周开阔，天花板列空则全周遮挡。因此未拍天花板时涉及天花板的档位恒为 0 分钟，界面会禁用这两档并提示先拍天花板；而「只拍外部」这一默认情形正常工作。

覆盖度按需求只统计主方向半圆：北半球 90 到 270 度，南半球取补集（270 到 360 与 0 到 90）。以 1 度步长采样该半圆，统计落在外部列任一线段内的比例，用于照片组卡片与采集页覆盖条。

**未覆盖语义的代价与界面补偿。** 「未拍到 = 开阔」这条规则把「遮挡未知」与「确认没有遮挡」压成了同一个结果，因此求值层无法回答「有多少直射是因为没拍才判的」。上海冬季只拍正南一个点时，冬至直射会算出 607 分钟（等于全天 100% 有太阳），与「对面什么都没有」完全等价——这个偏差方向是乐观的，在买房场景下代价最高。故结果页主结果卡下常驻一行覆盖率提示：低于 60% 红色、中间黄色、扫满不着色，点数不足两个时直接提示「拍摄点太少，结果多半偏乐观」。覆盖率取自 `Summaries.coveragePercent`，与采集页覆盖条同源。**「仅天花板」档必须换一套口径**：那一档未拍到 = 全遮挡，拍得越少结论越偏向「没太阳」，即偏差方向相反；且外部区覆盖率与该档结论无关，故改用天花板区自己的覆盖率，文案也从「偏乐观」换成「偏保守」。

取景器的遮挡填充只填已拍方位（`obstructionAt` 返回 -∞ 的方位跳过不填），否则未测区域会被画成「矮天际线」而误读成「测过了但不挡」。代价是填充区无法同时充当未测提示，未测仍由覆盖条与结果页覆盖率承担。填充方位按**全周**扫描，与求值用的 `Skyline` 同口径（覆盖度统计仍只算主半圆，见上一段），否则拍到主半圆之外的点会「算进结果却看不见」。未覆盖方位断开时必须把已画的那一段单独闭合：可见半视角（约 65 度）略大于已拍跨度时，循环结束时若不做这件事，已画段会被一并丢弃、填充整块消失。

**负仰角的处理。** 仰角需求域是 0..90，但低于 0 的读数不代表「平视而略低」，而是视线落在地面（竖持时手腕自然下垂约 -20 度）。这类读数若照旧入库会被 `coerceIn(0, 90)` 压成 0，装成一条贴地的矮墙且事后无法复原，故在入口由 `AimGuard.canCapture` 拦下（阈值 -3 度，不取 0 是为了放过远处 3 度以内的矮楼顶）。判定与警告滞回抽为 `ui/capture/AimGuard.kt` 的纯函数以便单测。取景器另有地平线以下的地面层（土色，仿飞机姿态仪）作为视觉提示。

## 6. 日照评估

`SunlightCalculator` 对一个组、一种计算档、一个日期，以 1 分钟步长从当地 0 时到 24 时采样太阳位置，逐分钟判定「可见直射」。三种档位：仅外部（默认，只要求太阳高于外部天际线）、外部+天花板（在前者基础上再要求太阳低于天花板上沿）、仅天花板（只做后者）。点列为空时按第 5 节的未覆盖规则退化，因此「仅外部」在未拍天花板时就等于纯外部判定，而涉及天花板的档位要求先拍天花板。可见分钟累加即当日直射时长；同时记录可见区间的起止时刻，供时间线绘制与「是否全部来自空隙」判定（全部可见分钟都落在某条经地平线段的开区间内时标注「全部来自空隙」）。

日出日落时刻单独扫描 -0.833 度阈值求得，供时间线两端与文字输出。全年曲线按同样方法逐日计算 365 天（闰年 366），在后台协程执行，结果缓存在内存。

国标辅助卡按 GB 50180-2018 预设给出「大寒日 8:00-16:00 有效直射」的窗口内累计分钟数，并列出各气候区的考核标准文字供用户对照，不做自动达标判定。窗口固定取大寒日 8 至 16 时真太阳时（`Summaries.gbWindowMinutes` 传 480/960 分），其余档位只出现在说明文字里，不参与计算。

说明文字里的阈值抄自该标准表 4.0.9：Ⅰ、Ⅱ、Ⅲ、Ⅶ 气候区大寒日 8 至 16 时 ≥2 h（城区常住人口不足 50 万 ≥3 h），Ⅳ 气候区大寒日 8 至 16 时 ≥3 h，Ⅴ、Ⅵ 气候区冬至日 9 至 15 时 ≥1 h。**这句只在中文界面出现**，其余七种语言在各自的 `strings.xml` 里取空串：气候区划是中国大陆的行政与气候划分，出了国境没有对照意义，译过去只会把一句「本地不适用」的标准说成通用结论。空串也是合法资源，`tools/check-locale-parity.py` 只比键集、占位符与换行数，不要求值非空；界面侧由 `ResultActivity` 在 `onCreate` 里按是否为空决定 `gbHint` 的 `isVisible`，否则那一行的高度与 6dp 间距会留在卡片底部。

## 7. 传感器管线

姿态使用 `TYPE_ROTATION_VECTOR`（加速度计、陀螺仪、磁力计融合），经 `getRotationMatrixFromVector` 得到 R 后按第 3 节方法直接算后摄视轴方位角与仰角，以及供叠加层使用的设备三轴世界向量。仰角以重力为基准不经罗盘，是最稳的量；方位角经磁偏角校正到真北：用 `android.hardware.GeomagneticField(lat, lon, alt, now)` 的 `declination` 加到磁方位上。该 API 内置世界地磁模型，无需联网与自带系数表。

罗盘校准取旋转矢量传感器的 `onAccuracyChanged` 精度回调（未回调前为未知态），读数精度取最近 24 个姿态样本的极差（0.8 度内为高、2.5 度内为中，更差为低），两枚药丸以绿/黄/红/灰分级显示，低或未校准时提示画 8 字。定位用系统自带 `LocationManager` 的多 provider 融合：API 31 及以上优先 `FUSED_PROVIDER`，与 `NETWORK_PROVIDER`、`GPS_PROVIDER` 并发取位，不申请网络权限、不引入第三方定位 SDK（零体积、零 key）。照片组管理页在 `onStart` 到 `onStop` 之间挂低频预热监听（无权限时静默跳过，可重复调用；退到后台即注销，避免 GPS 持续耗电与 LocationManager 持有页面上下文造成泄漏），建组取位先用 5 分钟内新鲜缓存（命中秒回不干等），实取时精度 ≤100 米立即返回、2.5 秒软超时采用已有最佳值、6 秒硬超时兜底。拿到经纬度后立即用 GeomagneticField 算磁偏角、用 `ZoneId.systemDefault()` 记录时区；组内保存快照，避免测量中途经纬度跳动引起真北基准漂移。

传感器到界面用一个 `OrientationSensor` 类封装：注册/注销、坐标换算（含显示旋转映射）、磁偏角叠加、平滑（对显示值做轻量低通，采集时刻直接取融合瞬时值，不引入额外延迟）；同一姿态顺带解出滚转角 `atan2(-right[2], up[2])`（屏幕「上」相对世界竖直的偏转，正值=机顶向右倒），供采集页显示左右倾斜。

## 8. 相机与照片管线

相机用 CameraX（`camera-core`、`camera-camera2`、`camera-lifecycle`、`camera-view`）：`PreviewView` 负责取景画面，`ImageCapture` 负责拍照，生命周期与 Activity 绑定。视场角从 `CameraCharacteristics` 的焦距与传感器物理尺寸算出（`Camera2CameraInfo` 取特征），失败时退回 65 度水平视场假设；视场角只影响叠加层与实景的贴合度，不影响记录的角度。

每次快门触发 `ImageCapture.takePicture` 存到 cacheDir 临时文件，随后：用 `ExifInterface` 写入拍摄点元数据（方位、仰角、分区、组名、序号、连线模式，编码为 JSON 放进 `TAG_USER_COMMENT`，同时写 GPS 经纬度与时间），然后发布到系统相册。Android 10 及以上走 MediaStore（`Pictures/D7/<组名>/`，同名组中最早创建者用裸名、后来者追加短 id 后缀以免相册里混放；IS_PENDING 流程），Android 9 及以下直接写入公共 Pictures 目录后扫描媒体库；发布完成后删除临时文件。这是一条三级梯子（实现在 `util/MediaFiles.kt` 的 `publishToPublicOrPrivate`，拍照与导出共用）：前两级都不成时退到应用私有 `files/captures/<组名>/`，该目录本就不在媒体库扫描范围里所以不登记，返回 `Uri.fromFile`；三级全失败才返回 null、照片才算丢。0.1.3 统一这条梯子时纠正了一处不一致——在 Android 10 及以上，MediaStore 失败原本直接返回 null，照片就此丢掉，而 `PhotoStore` 的类注释写的正是「失败回退应用私有 captures/」。记录点到组数据里保存的是 MediaStore 的 content URI，界面缩略图经 `ContentResolver` 异步加载并降采样，URI 失效时显示占位图。

照片落在系统相册是需求的核心交互之一（「相册右边的照片更旧」），因此正常路径不为照片另存副本；私有目录里的 `files/captures/` 只是上面那条梯子掉到最后一级的兜底，不是常备备份。导出功能提供图片与 CSV 的自救通道。权限方面：相机为必需；定位可选（用户也可手输经纬度）；Android 9 及以下写公共目录需要存储权限，Android 10 及以上不需要。

快门按钮的两种按法在 UI 层判定：按下即挂一个 450 毫秒的延时任务，到点当场记点（外部区：该点与上一点之间为经地平线推断段）并给一次长按震动，此后按住多久都不再记、抬手也不补记，必须松手再按才算下一次；未到阈值就抬手视为短按（直接连线）。手指滑出按钮（`ACTION_MOVE` 越界）或 `ACTION_CANCEL` 会撤掉那个延时任务，因此没到阈值就滑开什么也不记；已经记下的长按点不因抬手而回滚。天花板区屏蔽长按——长按时不拍照并给出提示「天花板区只支持短按连线」。删除键同样长按生效：按下即锁定一个「待删点」（按当前瞄准方位角取右侧最近点，见第 10 节），按住期间不再重算、不重复触发，滑出按钮或 `ACTION_CANCEL` 即取消且不删点。

按住期间的反馈由一个 450 毫秒的 `ValueAnimator` 驱动：取景器内准星下方浮出提示文字、准星外画一圈琥珀色进度环、快门上叠一枚系统转圈指示，三者共用同一份进度值；松手、滑出或页面停止即撤掉，不留半截进度。

**「不拍照」模式。** 采集页读数行里 `+180°` 药丸左边有一枚同尺寸的「不拍照」药丸（默认关，状态持久化）。开启后快门只落角度点（`PointRecord.photoUri` 为空），不调 `ImageCapture.takePicture`、不写 EXIF、不发布到相册：取景预览、对焦与手电筒照常（预览就是瞄准工具），省掉的是 JPEG 编码与相册 IO 这一整条。无图点在下游本就容错——结果页缩略图显示占位框、删组按 `mapNotNull` 过滤、导出 CSV 本就不含照片字段、批量编辑增删行也会产出无图点。切换时给一句 Toast：这个模式的后果（相册里没有照片）在按下快门之前完全看不见，只靠药丸变色不够。

## 9. 取景器投影与叠加层

叠加层是一个覆盖在 `PreviewView` 上的自定义 View，每帧（传感器更新驱动，约 30 到 60 次每秒，做 30 毫秒节流）按当前姿态重绘：参考弧、拍摄点与连线、十字线、空隙标记。

十字线画在画面正中，画法收在 `view/AimCrosshair.kt` 的 `AimCrosshair` 里。它对应的是后摄视轴（第 3 节那条「记录拍摄点所用的方向」），而针孔投影的主点就在 `width/2, height/2`，所以视轴与画面正中必定是同一处。结果页点开的大图用同一份画法（`AimCrosshairView`）把准星叠在照片正中：照片按 `fitCenter` 居中缩放，其中心与屏幕上那个 View 的中心重合，于是准星正好落在「当时对着的那一点」上——用户在大图上看到的点，必须就是拍照时对的那个点，所以两处共用一份画法而不是各写一遍，`AimCrosshairTest` 拿同一尺寸把两处各渲染一遍逐像素比对。尺寸由 `AimCrosshair.sizePx` 自己报（约 33dp，取偶数是让中心落在整像素上，奇数会因抗锯齿左右不对称而看着发歪）。若哪天大图改成非居中的裁剪，那边必须跟着改。

投影用针孔模型：世界方向向量 v（由方位角、仰角生成 ENU 单位向量）依次点乘相机的右、上、前三个世界向量得到相机坐标 (x, y, z)，屏幕坐标 = 中心 + (x/z, -y/z) × 焦距像素（焦距由视场角推算）；z ≤ 0.01 的方向视为在相机背后予以剔除，剔除处把折线断开，因此跨视场边缘的弧线只画落在画面里的那一段，近天顶段自然裁剪。相机的右、上向量按显示旋转（`Display.getRotation()` 四种取值）从设备三轴映射得到；采集页已锁竖屏，常规使用即 ROTATION_0，四种映射用于兜底分屏等窗口形态。

参考弧按赤纬生成：对给定赤纬按小时角采样全天太阳位置，得到 (az, el) 序列后投影成折线；采样只依赖纬度与赤纬，按赤纬缓存在叠加层内（定位更新时失效），姿态变化仅重投影、不重算天文位置。采样取几何高度角（不含大气折射），与天际线求值的判定基准一致。夏至橙、春秋分红、冬至蓝（均虚线）、今日白（实线）、当前太阳金（琥珀圆盘，见下段）、地平线与铅垂线灰短虚线（默认隐藏）。铅垂线定义为当前十字线方位角上的等方位弧（仰角 0 到 90），作为对垂直参考。显隐由「显示线」设置控制，默认显示四条太阳弧、当前太阳、拍摄点连线、地面层与楼体遮挡区，隐藏地平线与铅垂线；十项的字面、线色、持久化键与默认值都记在 `ui/Settings.kt` 的 `CaptureLine` 枚举里，对话框按它的顺序铺条目，设置按条目本身读写，下标不再跨文件约定。

「当前太阳」是今日轨迹上的一枚琥珀圆盘（盘 + 柔光 + 细黑描边，亮天空与暗楼体上都看得见）。位置逐帧现算：`Solar.position(now, lat, lon, refraction = false)`，赤纬与今日弧用的是同一个值，所以那个点必定落在今日那条白线上，不会浮在它旁边；仰角低于 -0.833 度（等效上缘 + 折射）就不画，与今日弧一起从画面里消失。这里刻意不按天缓存：每帧二十来次超越函数，而同帧把上千个采样点投到屏幕上要两千来次三角函数，为省这点开销去维护「改经度 / 跨日 / 跨半球时缓存何时作废」不划算——真正值得按天缓存的是结果页那种一天一千四百多个采样点的算法（`Solar.DayTrack`）。叠加层为此多要两样东西：一个经度（`lonDeg`，参考弧用不到它）和一个可替换的时刻源（`nowMillis`，测试用它钉住「此刻」，否则「太阳在地平线下不画」这类用例只能等到夜里跑）。时刻在 `onDraw` 里只取一次、今日弧与太阳标记共用，跨午夜时两处才不会落在不同的日子上；纬度的同值赋值加了一次短路，免得采集页每次收到数据都白清一遍弧缓存。太阳标记画在参考弧这一层，用户自己的拍摄点与连线仍压在最上面。

地面层与遮挡填充都画在参考弧之下，只依赖拍摄点与姿态，同样逐帧投影：地面层沿方位每 4 度取一点（91 点）填地平线以下；遮挡填充沿全周取折线边缘仰角（`Skyline.obstructionAt`，与求值同一口径），**往它挡光的那一侧铺**：外部建筑区填折线以下（楼挡的是折线以下那片天），天花板区填折线以上（窗框挡的是折线以上那片天，太阳要低于折线才照得进来）——填错一边的后果是「该看见遮挡的半屏干干净净，太阳根本照不到的那侧反而一片白」；采样方位由 `Skyline.fillAzimuths` 给出：等间距网格（每 2 度）**并上每个拍摄点的方位及其左右各 0.001 度**。这一步不是省事，而是准确性的前提——`obstructionAt` 是方位角的分段线性函数，折点恰好落在拍摄点方位上，而经地平线段在端点处是竖直跳变。照等间距网格采样会同时犯两个错：顶边停在网格点上、与拍摄点之间只连一根弦（实测最坏差 33.6 度，屏幕中心约 15 像素每度）；缺口端头那条本该竖直的崖边被摊成两度宽的斜线。两者闭合时都把边线延伸到远超屏幕的 `FAR_REACH` 后交给画布裁切，不在可见段内部猜闭合点——那样会拉出横穿画面的错误斜边。方向一律取[世界下方的屏幕方向]（`Projector.screenDown`）而非屏幕正下方，手机横过来时世界下方在画面里偏到了侧面，两者必须一致；天花板区取同一方向的反向（`fillAbove`），正对天顶时该方向退化为零向量、两侧都无从谈起，此时整层不画（照直闭合会拉出一条横穿天空的弦）。

拍摄点渲染：每个点画白色圆点与序号（外部 1..n、天花板 1..n 各自编号），点与点之间按模式画线——直接连线画白色实线；经地平线段拆三段画月灰虚线（上点垂直降到地平线、沿地平线横走、垂直升到新点）。连线折线由 `:core` 的 `SkylineShape.direct/viaHorizon` 展开成 `AzElTrack`（两条等长数组），参考弧采样、地平线与铅垂线也用同一个类型，叠加层里只有一个 `drawTrack` 负责把它们投到屏幕并断线，导出参考图按同一份采样取点，保证「求值、叠加层、导出图」三处几何语义一致；「显示线」关掉拍摄点连线时点与线一并隐藏。填充层的顶边也必须与这条线严丝合缝，所以它走的是同一批折点（见上）。覆盖条画在取景器下方，横轴为 90 到 270 度（南半球镜像），已达区间白色、未达区间炭灰，同时可点击该条的「显示线」按钮打开线显隐设置。

## 10. 界面与交互

四个界面：

照片组管理是全应用入口。卡片显示组名、冬至结论（该组当前默认档位下的冬至日直射时长，未拍显示「未测」）、采集日期与经纬度（**经度在前、纬度在后**，与对话框里经度在上、纬度在下一致）、外部区点数与覆盖率、天花板区状态（点数或未拍虚框）。点卡片进结果页（一个点都没有的空组直接进采集页），长按弹出编辑/删除/导出（编辑打开与结果页共用的那个 `ui/GroupEditor.kt` 对话框：组名、经纬度、时区一起改；删除对话框提供「同时删除相册中的 N 张照片」复选框，默认不勾；组内没有带照片的点时不显示该框，勾选确定后逐个删除照片 URI，失败数量以提示告知），右上「+ 新建」走建组对话框（组名已预填、经纬度两行、一颗「GPS」药丸与状态行、随输入联动的南北半球提示）。建组对话框一打开就做三件事：把光标放进组名（键盘同时弹出）、自动定位一次、组名预填成 `group_name_default`。自动定位**只填用户还没动过的坐标**（点开时先记下两格的文本，回来时文本没变才写）：键盘是弹着的，用户很可能先改组名再手输经纬度，几秒后回来的定位结果不能把刚敲的数字盖掉；手动点「GPS」则一律覆盖。原先状态行每秒刷新一次的「定位能力」提示（GPS／网络定位／系统融合是否可用与当前精度）随之删掉——自动定位在对话框打开的同一下就启动，那行字永远露不出来，`LocationProvider.capability()` 与 `LocationCapability` 也一并移除。确认后直接进入采集页开拍。「+ 新建」左侧依次是「文A」语言键与一枚常亮图标键：「文A」点开是单选对话框，列出「跟随系统 + 8 种语言」，选中后立即重建生效（见附录 B）；常亮键是纯图形无文字（开=琥珀、关=月灰，与采集页手电筒/对焦键同一套开关语义），默认开，点一下即写偏好**并当场改本窗口的** `FLAG_KEEP_SCREEN_ON`，三个页面共用这一个开关。

经纬度两行由「轴名 + 输入框 + 半球后缀」组成：左边 `LON`/`LAT`，右边 `°E`/`°N`，后缀跟着输入的正负逐字符更新（填 `-74.006` 它自己变成 `°W`），绑定逻辑是 `ui/CoordinateFields.kt` 的 `bindHemisphereSuffix`。后缀不写死是因为 `-74.0 °E` 这种自相矛盾的标签会让人以为自己录错了方向；轴名与后缀用的是与 `Format.coordinate` 同一套记号（见附录 B 的豁免说明）。两行而不是并排：并排时每格只剩半幅宽，`121.470000` 这种六位小数会被拦腰截断。

组名与经纬度三格都开 `android:selectAllOnFocus`，进焦点即全选；**全选之后再点同一个框，光标落到点击处**，不再整段选中——这是 `TextView` 的原生行为，不需要自己写触摸监听去区分「首次点击」与「重复点击」（自己写还要在 ACTION_DOWN 里抢先记下选区，反而更绕）。两种意图因此各自有着落：敲第一个键顶掉旧值，点一下就能改一两个字符。时区那一格不开这个属性：`Asia/Kolkata` 这类值常要改的是后半截，一进焦点就全选反而得先取消选区。

时区是照片组的属性（`GroupRecord.zoneId`），建组时取手机当前时区，两个编辑入口都能改。它只影响钟面时间与时间线的横轴刻度，不影响太阳的几何位置，但手机时区与房子时区不一致时（拿地图坐标远程替外地的房子量），日出日落与整条时间线会整体偏几个小时。三种选法都落成同一个 id 再写库，不给 `GroupRecord` 加「模式」字段：加字段能让「按经度」在改经纬度时自动跟随，代价是多一个持久化字段、多一套显示与迁移分支，而这里只要用户再点一次那个图标键。换算与解析集中在 `util/Zones.kt`（纯 `java.time`，可 JVM 单测）：`system()` 取本机时区，`fromLongitude()` 按每 15° 一个时区四舍五入到最近的整点并写成 `UTC±hh:mm`（地球上没有「按经纬度查 IANA 名」的标准做法，时区边界跟着国界走，所以这里给的是太阳意义上的当地时区；写 `UTC±hh:mm` 而不是裸偏移，是为了与 `Asia/Kolkata` 一眼区分，也不会在零偏移时显示成一个孤零零的 `Z`），`parse()` 先按 IANA 名认、再按带符号偏移量认，并把 `+5:30` 这类「小时一位」的写法补成 `ZoneOffset` 认得的两位。对话框里所以看不到「时区」二字：输入框用数值样例 `+05:30` 当占位符（**故意用带半小时的时区**，让用户知道偏移量可以有分钟；不带 `UTC` 前缀是因为加上它就会把这一格撑到贴着右边的图标键，而 `+05:30` 本来就是最短的合法写法），不合法的输入也只报格式样例 `Asia/Kolkata · +05:30`，两个图标键的 `contentDescription` 是「按下去会写进输入框的那个值」，读屏据此报出具体时区。这几条与经纬度样例一样标 `translatable="false"`，八种语言共用一份（见附录 B）。

采集页自上而下：标题栏（左上角返回键、组名与经纬度元信息）、大号仰角与方位读数（读数行右侧依次为「不拍照」与 `+180°` 两枚药丸）、罗盘校准与读数精度两枚分级药丸及滚转角读数（左右倾斜，正值=机顶向右倒，非航向）、取景器（左上分区 chip：外部建筑/天花板，右侧为覆盖条与「显示线」）、取景器下方的瞄准警告红条（镜头低于地平线时常驻，默认隐藏）、方向提示（北半球「面向南，西·先拍 → 东·后拍」、南半球镜像）、底部一行五键（左「完成」，快门两侧依次为手电筒与对焦两枚纯图标键，右「删除」）。快门短按：抬手时记录当前十字线指向为新的拍摄点并与上一点直接连线；长按（外部区）：按住满 0.45 秒即当场记录并以经地平线推断段与上一点连接，继续按住不重复记点；天花板区仅短按，且与上一点直接连线。两种按法都在记录前经 `AimGuard.canCapture` 校验仰角，低于 -3 度视为瞄到地面而拒绝。删除键长按：在当前分区内删除「十字线右侧、离当前瞄准方位最近」的一个点（判定为相对当前方位角的顺时针角距最小者，即 `normalize(az_point - az_now) ∈ (0°, 180°)` 中角距最小），按住一次只删一个；左侧的点不受影响。短按不删任何东西，只弹一句「长按删右侧最近点」——这句提示原先常驻在删除键下方、占掉一整行，改成按需弹一次后那一行的高度还给了取景器。删点的同时把该点带的那张照片从相册里删掉——留着它只会是一张再也对不上任何点的图。`photoUri` 为空是常态而非异常（「不拍照」模式录的点、结果页批量编辑新增的点、继承不到照片的点），直接跳过；删不掉也静默（照片可能早已被用户在相册里清掉，而 `ContentResolver.delete` 对「本来就没有」与「没权限」一律回 0，分不开），批量删组的路径才会报「N 张失败」。完成键保存并跳转结果页。覆盖条之外，取景器内直接画出已拍点与连线（第 9 节）。页面锁竖屏运行，横屏时保持竖屏版式（见第 10 节）。续拍：从结果页「回采集续拍」返回该组，新点按分区各自原序续接。

结果页自上而下：左上角返回键、标题与元信息（元信息那行右侧的「编辑」打开组编辑对话框，见第 10 节开头；它改的经纬度与时区都是曲线与钟面时间的输入，所以曲线缓存键必须含这三样，见下）、日期药丸（冬至/大寒/春分/夏至/自定义，自定义弹日期选择器；这一行与下一行的计算档都放在横向滚动容器里——中文下五个药丸排得下，德俄等长词语言下一行放不下，而横向 LinearLayout 溢出时不报错、只是把选项默默推出屏幕，用户根本点不到）、计算档三档（仅外部默认/外部+天花板/仅天花板）、主结果卡（选中日期在该档下的直射时长、日出日落、来自空隙标注，以及常驻的主半圆覆盖率提示，见第 5 节）、当天时间线（日出到日落一条横条，白=直射、炭灰=被挡，标注起止时刻）、国标卡（大寒 8:00-16:00 有效直射与预设说明）、全年曲线（365 天时长折线，横轴月份刻度）、点列区（每行：分区标记、序号、缩略图、方位、仰角、与右边相邻点（拍摄序前一点，即列表上一行）的连线模式切换按钮、删除按钮；点缩略图整屏看大图（黑底、按比例缩到屏幕内、点任意处或返回键关闭，按屏幕最长边降采样解码——原图可能四千万像素；照片正中叠一个准星，见第 9 节）；缩略图没有照片时不挂监听，否则它会吃掉本该落到整行的点击；点删除按钮删这一个点并连带删掉它的照片，与采集页长按删除同一套语义；整行可点开单点角度编辑；标题行右侧「编辑」按钮打开批量编辑；天花板点无模式切换）、底部导出 CSV / 导出图片 / 回采集续拍。所有计算在后台协程执行，界面先显示上次缓存或加载态。

结果页点列区的编辑动作直接改组数据：删除任意点；切换某点与右边相邻点（拍摄序前一点，列表上一行；存储上段即前一点的 `gapAfter`）的连线模式（直接连线 ⇄ 走地平线，仅外部点可切换）。删除点后其前后两点的连接改为直接连线（避免悬空的经地平线段），这与「删除即撤掉该点及其连接段」一致。

角度编辑分两级且一律「强行」生效（不受照片限制）。单点：点击某行弹出对话框改写方位角与仰角，照片与拍摄时间保留；方位角规范化到 [0, 360)，仰角限制到 [0, 90]。批量：点列标题行右侧「编辑」按钮打开批量编辑器，外部区与天花板区各一段多行文本，逐行「方位角 仰角 [horizon]」（第三词写 horizon 或 h、0 均可，大小写不敏感，表示该行与上一行即右边相邻点之间经地平线；首行没有上一行，标 horizon 会被整单拒绝、两区都不落库），可先填方位偏移与仰角偏移、点「应用偏移」对全文整体平移再确认；确认时照片继承优先按 (az, el) 精确匹配旧点（行序重排也能对上），无法一一匹配且行数不变时按序继承原照片与拍摄时间，增删行则新点为无图点（photoUri 为空，界面显示占位缩略图）。因此不拍照也能手工搭出完整点列，供计算、导出与测试使用。

分屏适配：所有 Activity 声明 `resizeableActivity=true`；采集页锁定竖屏（横屏时取景区几乎无面积，保持竖屏版式最省），其余页面不锁方向。分屏时系统忽略方向请求，采集页按窗口形状缩放、以留边呈现，取景与拍摄照常。`configChanges` 声明常见尺寸变化避免拖动分屏时重建；相机页布局按「读数区／可伸缩中段／底部按键区」三层权重划分，窗口变矮时先压缩取景器，底部快门与删除、完成键始终留在屏幕内不被截断。相机在分屏下按平台允许情况运行，不额外做多窗口互斥逻辑。

## 11. 数据模型与持久化

组数据用 kotlinx.serialization 序列化为 JSON，存 `filesDir/groups.json`；写入在单线程后台执行器上异步进行（不阻塞界面线程），采用「先写临时文件再原子改名」，文件系统不支持原子改名时回退普通覆盖改名（极端写失败保留内存态、下次修改再试）。**读不动时先把原文件改名成 `groups.json.<毫秒时间戳>.bad` 留档**，再当空库用：不这么做的话，紧接着的第一次修改就会把这份空库整份写回同一路径，用户的照片组在盘上无声消失、内存里看不出任何异常。名字带时间戳是为了永不覆盖更早的坏档（这类文件只增不减，而读失败本就罕见）。照片本体在系统相册，JSON 里只存 URI。模式如下：

```json
{
  "version": 1,
  "groups": [
    {
      "id": "uuid",
      "name": "阳台",
      "lat": 31.23, "lon": 121.47, "altitude": 12.0,
      "zoneId": "Asia/Shanghai",
      "createdAt": 1759400000000, "updatedAt": 1759400000000,
      "external": [
        {"az": 236.2, "el": 18.4, "gapAfter": false, "photoUri": "content://...", "takenAt": 1759400000000}
      ],
      "ceiling": [
        {"az": 182.0, "el": 64.8, "gapAfter": false, "photoUri": "content://...", "takenAt": 1759400000000}
      ]
    }
  ]
}
```

导出提供两条通道：CSV（组信息、分区、序号、方位、仰角、连线模式、拍摄时间，以及所选日期的逐分钟可见性）写入 `Downloads/D7/`；导出图片把「天际线极坐标图 + 关键结论」渲染为 PNG 存入相册 `Pictures/D7/`。两者都走 MediaStore（低版本走公共目录+扫描）。

**存档导出/导入**（`data/StoreTransfer.kt`）是另一条自救通道，搬的是数据本身而不是给人看的东西。两个入口在照片组页右上角，走 SAF（`CreateDocument` / `OpenDocument`），落点与来源都由用户当场选，所以不需要任何存储权限，app 也不指定目录。导出的文本与落盘**同源**：两边共用 `StoreJson`，`exportedTextIsWhatTheRepositoryItselfWrites` 这条用例把导出物写进仓库真正读的那个文件来钉住它，否则「导出的文件能被本机导入」会退化成巧合。

导入**是整份替换而不是合并**：合并要处理 id 撞车、同一组两份点列谁为准，没有一条规则算得上「显然正确」，反倒会让用户在两次导入后拿到一份自己也说不清的混合数据。这里的输入来自应用之外，是全局唯一一处「别人的字节直接进模型」的入口，所以除了格式（序列化器把关），还要过一遍语义校验：经纬度与海拔的范围、时区能不能 `ZoneId.of`、id 不许空也不许重复、组数与总点数上限，任一项不过就整份拒绝。**时区那条是真正的坑**，格式完全合法，炸点却在几小时后算日照的那一步，离现场很远。角度则归一而不是拒绝（方位 0..360、仰角 0..90），与 `updatePointAngles` 同一套规则：手改过的角度常见，而一个 370 度的方位角意思明确，没必要让它把整份存档变成不可用。

**系统备份。** `allowBackup="true"`，规则分两份写，`res/xml/backup_rules.xml` 管 Android 11 及以下，`res/xml/data_extraction_rules.xml` 管 12 及以上。两份都把 `files/captures/` 排除在**云端备份**之外：那是照片落盘三级兜底最后一跳的副本，一张几 MB，而 Auto Backup 的单应用上限是 25MB，顶破之后失败的是整个应用的备份，连 `groups.json` 一起丢。换机传输那一份不排，两台手机直连搬数据没有那个上限，把兜底副本一起带走反而是好事。

用户偏好集中在 `ui/Settings.kt` 的 `Settings` 里读写（SharedPreferences）：屏幕常亮、十条显示线、「不拍照」与 `+180°`。三个页面各有自己要读的项（照片组页读常亮，采集页读其余），但它们是同一份偏好，分成两个存储只会让人问「这个开关记在哪儿」。偏好文件沿用历史名 `capture`：它只是内部文件名、对用户不可见，而改名会把用户已经调好的显示线一次性清回默认值。界面语言另存 `SharedPreferences("locale")`——它要在 `attachBaseContext` 阶段就被读到，比偏好早一整步（见附录 B）。

## 12. 构建与工具链

工程使用 Gradle Wrapper 固定版本，不依赖本机 Gradle。版本矩阵：JDK 17（Temurin）、Gradle 8.11.1、Android Gradle Plugin 8.7.3、Kotlin 2.0.21（含 kotlinx.serialization 插件）、compileSdk 35、targetSdk 35、minSdk 26。依赖：androidx core-ktx / activity-ktx / lifecycle-runtime-ktx、CameraX 1.4.x（core、camera2、lifecycle、view；camera-view 上 exclude 掉 appCompat，它对该库零引用）、kotlinx-serialization-json、androidx exifinterface、coroutines（不引入 Material 库）；测试为 `:core` 的 JUnit4 单测，以及 `:app` 的仪器测试（Espresso、espresso-intents、runner、rules、ext-junit、uiautomator）。release 构建开启 R8 缩减（`isMinifyEnabled`/`isShrinkResources`），只保留 zh/en/ja/ko/de/fr/es/ru 八种语言资源、裁剪 x86/x86_64 ABI、图标转 WebP 以压缩体积。

**APK 体积预算（0.1.3，R8 产物 602,258 字节）。** 逐项实测（下表为条目未压缩体积）：

| 项 | 字节 | 说明 |
| --- | --- | --- |
| `classes.dex` | 880,284 | 压缩后约占 48%，APK 的大头 |
| `resources.arsc` | 106,308 | 存根不压缩；八种语言的字符串池占大头 |
| `res/` | 71,144 | 11 个布局 41.3 KB + 23 个 drawable 16.3 KB + 启动器图标 10.9 KB + `xml/locales_config.xml` 1.0 KB |
| `AndroidManifest.xml` | 7,388 | |
| `lib/` | 8,272 | arm64 4.8 KB + v7a 3.4 KB，均为 `libsurface_util_jni.so` |
| `assets/` | 487 | baseline profile，启动加速用 |

已做的三项减法，各有实测数字与代价说明：

- **`packaging.resources.excludes` 剔除 `**/*.kotlin_builtins` 与 `**/*.kotlin_metadata`**（7 个文件共 29,945 字节，占 trim 前包 4.8%）。它们是 Kotlin 编译器与 kotlin-reflect 读的类库元数据；本工程没有 kotlin-reflect 依赖，也不做任何运行时反射（`kotlin.reflect`、`KClass.members`、`typeOf` 均未出现），kotlinx.serialization 的序列化器是编译期生成的代码、不读这些文件。613,873 → 602,318 字节。这 7 个文件是 deflate 存放的，压缩后合计只剩 10,468 字节，所以 APK 减幅（11,555）不到它们未压缩体积的一半。**逐条比就看未压缩，看总量就看压缩后，两个口径不能混着比。**
- **`jniLibs.excludes` 剔除 camera-core 的 `libimage_processing_util_jni.so`**（arm64 29,008 + v7a 20,380 = 49,388 字节）。它只服务 YUV/bitmap 互转与 OpenGL 渲染，D7 只有 Preview + ImageCapture 两条用例，走不到这些 native 方法；`ImageProcessingUtil` 类本身保留（R8 按 native 方法名 keep），缺的只是库文件。实测 APK 600,281 → 534,541 字节（−11%）。注意减幅比 49,388 字节还多出约 16 KB：这两个 `.so` 与 `lib/` 里留下的 `libsurface_util_jni.so` 一样是原样存放、按页对齐的，剔除它们时各自的页对齐填充也跟着消失。拆开看是条目数据 −49,405、对齐填充 −15,999、尾部 −184。
- **`resourceConfigurations` 限定八种语言**，把 AndroidX 自带的上百种翻译挡在包外。代价：多六种语言（相对只留 zh+en）实测 63,680 字节，占包的 10.6%——这是为界面多语言功能付的费用，属于产品取舍，不是冗余。这个数字随着译文增减而变化：`result_gb_hint` 在七种语言里清空之后它降过一次，这一版又把九条 `gps_cap_*` 与三条时区相关文案从七种非中文语言里删掉，累计从 66,852 降到 63,680。

**验证过但决定不做的精简**（写在这里是为了下一个人别再试一遍）：

- **exclude 掉 `androidx.camera:camera-video`**。它经 camera-core / camera-camera2 / camera-lifecycle / camera-view 四条 `api` 边进依赖树，是未混淆包里的第三大包（379 个类，占非混淆 dex 的 6.1%），看着最像冗余。但 R8 已经把它删干净了（混淆包里 `androidx/camera/video` 命中数为 0）：实测把四条边全 exclude 后重新构建，APK 只从 602,318 变成 602,147 字节，**省 171 字节**，却要承担 `PreviewView` 触碰 video 类时 `NoClassDefFoundError` 的风险。不值得。
- **把启动器图标 `mipmap/ic_launcher_foreground.webp`（10,268 字节）改成矢量**。它是带径向光晕与玻璃渐变的照片级位图（VP8 有损，432×432），矢量化必然掉画质，换 8 KB 不值。
- **用 `org.json` 替掉 kotlinx.serialization**。序列化相关类在混淆包里约 62 个，估算值不了多少字节，而手写 JSON 会实打实地牺牲可维护性。
- **打开 R8 的 `com.android.tools.r8.retainLambdaInvokes`**（加进 `org.gradle.jvmargs`）。混淆后的 dex 里有 **147 个 `$$ExternalSyntheticLambda` 类、合计 67,284 字节**（占类数据 10%），其中一大半在库里：`androidx.camera.core.processing.DefaultSurfaceProcessor` 独占 6 个，最大的一个 2,299 字节。看着像是「让 R8 别把 lambda 脱糖成类」的正路。实测**没有任何变化**：属性确实到了构建进程（探针任务打印 `PROBE=true`），`minifyReleaseWithR8 --rerun` 重跑之后 dex 仍是 880,284 字节、APK 仍是 602,258 字节，一个字节没少。别再试了。
- **exclude 掉 `androidx.profileinstaller`**。它是 `androidx.startup` 拖进来的，混淆包里 17 个类、15,328 字节，加上 `assets/dexopt/baseline.prof`（369 字节）与 `.profm`（118 字节）——而那份基线配置小到几乎不含规则（369 字节），也就是说这个库今天实际上什么也没做，估省 7~8 KB。**但要在 manifest 里一并摘掉 `androidx.startup` 的 `InitializationProvider` 初始化项**：那个元数据指向 `androidx.profileinstaller.ProfileInstallReceiver`，留着就是启动时 `ClassNotFoundException` → `IllegalStateException` 的崩溃，1.2% 的体积换一条启动路径不值得。

dex 的实际构成（`apkanalyzer dex packages` 逐类归并到原包名，2026-10-05 实测）：`androidx.camera` 344,083 字节 / 509 类（camera-core 200,316、camera-camera2 112,774、camera-view 27,093、camera-lifecycle 3,900），本工程 88,679 / 129（最大三个类是 `CaptureActivity` 7,782、`ViewfinderOverlayView` 5,641、`ResultActivity` 5,497），`kotlinx.coroutines` 58,576 / 119，`androidx.exifinterface` 36,109 / 8（写 EXIF 标签的字符串表占了大头，没有更省的替代品），`kotlinx.serialization` 27,587 / 54。剩下三项都不是能砍的：相机是本 app 的本体，协程撑着 StateFlow 与生命周期作用域，EXIF 是照片落盘的一环。**资源侧也没有余量**：226 条字符串、11 个布局、23 个 drawable 逐个查过引用，孤儿资源为 0；布局里唯一明显的重复是建组与编辑两个对话框共用的「组名 + 两行经纬度」（未压缩各约 3 KB），用 `<include>` 收成一份能省 res/ 约 2.8 KB 未压缩、1 KB 压缩，代价是视图绑定多一层 `view.fields.lonInput` 的间接，不值得。

对照参考：**关掉 R8 的 release 包是 2,903,771 字节**，dex 从 880,284 涨到 7,809,312——R8 砍掉 88.7% 的 dex。需要排查「R8 藏起来的东西」时，可临时把 `isMinifyEnabled`/`isShrinkResources` 置 false 打一份对照包，但交付产物必须始终是 R8 包。

把 0.1.3 这一版拉直看：上面那三步减法做完是 602,318 字节。随后的结构重构（合并三处按键手势、折线采样收成 `AzElTrack`、落盘三级兜底收进 util、导出结果删掉冗余字段）对产物体积的影响是零——前后打 release 包都是 602,318 字节，dex 从 875,640 走到 875,280 的那点零头全被 zipalign 的填充吃掉。同一版最后补的三处界面改动（采集页与结果页的返回键、照片组页的常亮键、删除键提示改 Toast）连同两枚矢量图标与八种语言各两条新文案，把包推到 604,518 字节：逐项看是 dex +688、`resources.arsc` +848、`res/` +2,808、`assets/` −33。**看这类改动值不值，看 dex 而不是看 APK 总量，后者在这些量级上量不出来。**

收尾的改动反过来把包压回 **603,458 字节**（−1,060），这也是同一版里唯一一次体积下降，原因值得记一笔：新增了一个准星控件（`view/AimCrosshair.kt`，dex 未压缩 +828）与一处布局（`res/` +188），同时把七种非中文语言的国标说明清成空串。`resources.arsc` 是**原样存放不压缩**的，字符串池里少掉的字节就是包上少掉的字节，这一项直接减了 1,128，比新增的两项加起来还多。压缩侧逐段对上：dex +473、`resources.arsc` −1,128、`res/` +70、`META-INF` +73、`assets/` +35、中央目录与对齐填充等 −583，合计 −1,060。各段压缩后的大小会牵动后续条目的对齐填充，所以最后那一项是链式位移的残差，不必逐字节解释。

再往后的「地点可改 + 时区可选 + 建组自动化」一批改动把包压到 **602,258 字节**（−1,200），又一次靠字符串池而不是靠砍功能：新增一个组编辑对话框（`res/` 未压缩 +6,512、dex 未压缩 +3,488），同时从八种语言里删掉 9 条 `gps_cap_*`（定位能力提示行随自动定位一起撤掉）、3 条时区相关文案，并把 7 条数值样例／轴名改成 `translatable="false"` 只存一份。压缩侧：dex +1,686、`resources.arsc` −2,968、`res/` +1,570、`META-INF` +74、中央目录与对齐填充等 −1,562，合计 −1,200。**这一版里唯一变重的大头是图标与布局**（新增两枚矢量图、两处布局各加一行），`resources.arsc` 那 −2,968 把它们全抵掉了。同一批改动还顺手删掉了 `LocationProvider.capability()`、`LocationCapability` 与三个 Kotlin 文件里的重复代码，这些对体积的影响量不出来，但少了一整条「对话框打开后每秒查一次定位能力」的路径。

本机缺什么装什么：JDK 与 Gradle 用 Homebrew 安装，Android SDK 用命令行工具（cmdline-tools）安装 platform-tools、platforms;android-35、build-tools;35.0.0 并接受许可。工程内 `gradle.properties` 指定 JDK 17 路径，保证命令行与 IDE 行为一致。

**release 签名。** 签名材料是仓库根目录的 `release.jks` 与 `keystore.properties`，两者均在 `.gitignore` 内，不入库。生成方式：

```
keytool -genkeypair -keystore release.jks -keyalg RSA -keysize 4096 \
  -validity 10000 -alias d7 \
  -dname "CN=D7 Sunlight, OU=io.github.hecate2, O=D7, L=Shanghai, ST=Shanghai, C=CN"
printf 'storeFile=release.jks\nstorePassword=<密码>\nkeyAlias=d7\nkeyPassword=<密码>\n' > keystore.properties
```

`app/build.gradle.kts` 读 `keystore.properties`，文件不存在时 release 产物**退化为未签名**而不报错——这样 clone 后的仓库仍能 `assembleRelease`，只是装不上设备。

**签名方案实测为仅 v2**（`apksigner verify` 结果：v1 false、v2 true、v3 false、v4 false）。这是 AGP 对 `minSdk 26` 的默认行为：v1/JAR 签名只在 minSdk < 24 时需要，APK 内确实没有任何 `.RSA`/`.SF`；v3 未启用表示暂不支持密钥轮换（key rotation），对个人应用无影响，若日后要启用可在 `signingConfigs` 里加 `enableV3Signing = true`。证书 DN 为 `CN=D7 Sunlight, OU=io.github.hecate2, O=D7, L=Shanghai, ST=Shanghai, C=CN`，RSA 4096（SHA384withRSA）、有效期 10000 天。

**私钥材料清单**：`release.jks`（含私钥与证书链，PKCS#12，权限 600）与 `keystore.properties`（`storeFile` / `storePassword` / `keyAlias=d7` / `keyPassword`）这两个文件即可对 APK 签名。**两者都必须备份且不得入库**：丢失或忘记密码后，已发布的包无法覆盖更新，另一台设备也无法安装同一应用（Android 要求签名一致）。`.gitignore` 已按`*.jks` 与 `keystore.properties` 两条规则排除。

常用命令：`./gradlew :core:test`（算法单测）、`./gradlew :app:assembleDebug`（调试包）、`./gradlew :app:assembleRelease`（发布包，产物名带版本号）、`./gradlew :app:connectedDebugAndroidTest`（仪器测试，在已连接的模拟器或真机上运行，多设备时用 `ANDROID_SERIAL` 指定；该任务结束会自动卸载应用）。装机若 `installDebug` 遇到 ddmlib 超时，可改用 `adb install -r` 直接安装。

反复调试单个测试类时用 `scripts/run-instrumentation.sh`：它走 `am instrument` 跳过 Gradle 的重新打包与安装，模拟器上全量 64 个用例约 39 秒（最慢一次 58 秒，那次是相机链路把两个按快门的用例拖成了等 20 秒超时）、单个类约 3 秒，而 `connectedAndroidTest` 即使命中缓存也要 35 秒以上（冷构建 57 秒）。可传类名或 `类#方法` 限定范围。脚本内把 `ANDROID_SERIAL` 默认为 `emulator-5554`——本机常同时连着真机，而 `connectedAndroidTest` 会跑遍所有连接设备，正式验收仍应显式指定设备。

仪器测试目前 64 例，分 `full`（模拟器，含多语言）与 `fast`（真机，跳过标了 `@NeedsI18n` 的用例）两种模式；用例数不是稳定值，文档里引用它的地方要跟着改。另有一类偶发失败要认得出来——`CaptureShutterGestureTest` 的长按用例等的是整条拍照链路（`takePicture` → EXIF 回写 → 整张 JPEG 发布到相册）落库，模拟器相机被别的负载拖慢时会等满 20 秒超时，而单独跑这个类稳定通过（约 3 秒）；它与代码改动无关，别据此回滚。

## 13. 实施阶段与提交计划

按下列顺序实施，每阶段一个可编译、可验证的提交：

其一，架构文档与 `.gitignore`（忽略 `.workbuddy/` 与 `img/archive/`，后者按需求不提交）。其二，Gradle 工程骨架（双模块、版本矩阵、图标资源与主题配色，目标：`assembleDebug` 通过）。其三，`:core` 算法模块与单元测试（太阳位置对拍与物理合理性断言、天际线求值、空隙、环绕、半球镜像、日照统计）。其四，数据层与照片组管理界面（JSON 仓库、建组/改名/删除、卡片）。其五，采集界面（CameraX、传感器、投影叠加层、两种快门、长按删除、完成、分区 chip、覆盖条、线显隐设置）。其六，结果界面（三种档位、日期药丸、时间线、全年曲线、点列编辑、导出）。其七，打磨与真机验证（分屏、图标、配色校对、装机实测与修正）。

## 14. 风险与对策

姿态读数在手机接近垂直时方位抖动（机身上缘水平投影趋零所致）：主用姿态是略微后仰瞄准楼顶，抖动可接受；显示层做低通平滑；测量值取瞬时融合值。采集页已锁竖屏，常规使用不会出现横屏版式；分屏小窗叠加横屏的组合下会走到显示旋转映射的 `ROTATION_90` / `ROTATION_270` 两分支。该映射已抽为纯函数 `sensor/ScreenRotation.kt`，四档逐档单测（并以「右 × 上 恒等于设备 +z」为不变量兜底），同时供相机焦距换算复用，避免两处各写一套符号约定；若真机发现叠加层整体左右镜像，对调该对象的分支即可，不影响记录数据。罗盘受阳台钢筋干扰属物理限制，界面提示画 8 字校准并在低精度时显著提示，数值不追求优于 2 到 4 度。不同厂商相机在多窗口下的可用性有差异，兜底方案是分屏下暂停预览但保留传感器读数。MediaStore URI 被外部清理时缩略图缺失、角度数据不受影响，导出功能提供数据自救。

## 附录 A：代码级实施清单

应用 ID（applicationId）与代码包名（namespace）均为 `io.github.hecate2.D7`（`7D` 与 `7d` 因段首为数字、不能用作包名段，故采用 `D7`）；`:core` 模块包名 `io.github.hecate2.D7.core`。

`:core` 文件清单与关键 API：

- `Solar.kt`：`data class SolarPosition(val azimuthDeg: Double, val elevationDeg: Double)`；`object Solar` 提供 `position(utcMillis: Long, latDeg: Double, lonDeg: Double, refraction: Boolean = true): SolarPosition`（NOAA 公式，见第 4 节）、`julianDay(utcMillis: Long): Double`。几何高度角配 -0.833 度阈值即日出日落。
- `Skyline.kt`：`data class ShotPoint(val azDeg: Double, val elDeg: Double, val viaHorizonAfter: Boolean = false)`；`class Skyline(points: List<ShotPoint>)` 提供 `obstructionAt(azDeg: Double): Double`（返回天际线边缘仰角；未覆盖方位返回 -∞，由判定方向分化为外部开阔与天花板全遮挡，见第 5 节）、`gapArcs(): List<Pair<Double, Double>>`、`coverage(points: Int = 360): BooleanArray`（按 1 度采样全周是否被覆盖，供覆盖条与覆盖率用）；`object SkylineShape` 提供 `direct(...)` 与 `viaHorizon(...)`，把线段按求值语义展开为 `AzElTrack`，取景器叠加层与导出参考图共用，保证「求值、叠加层、导出图」三处语义一致。
- `AzElTrack.kt`：`class AzElTrack(val azimuths: DoubleArray, val elevations: DoubleArray)`，两条等长数组按下标配对的一串世界方向采样。求值侧的折线展开、叠加层的参考弧与两条参考线、导出参考图四处共用它；`init` 里校验两数组等长，长度对不上当场报错而不是留到某次投影时越界。
- `Sunlight.kt`：`enum class CalcMode { EXTERNAL_ONLY, EXTERNAL_AND_CEILING, CEILING_ONLY }`；`data class DailySunlight(date, sunriseMinute: Int?, sunsetMinute: Int?, directMinutes: Int, visibleIntervals: List<IntRange>, allFromGap: Boolean, daylightMinutes: Int)`（分钟序号自当地 0 时起）；`object SunlightEvaluator` 提供 `evaluate(lat, lon, zoneId: String, external: List<ShotPoint>, ceiling: List<ShotPoint>, mode, date: LocalDate, stepMinutes: Int = 1): DailySunlight`、`yearlyCurve(..., year: Int, mode): List<Double>`、`windowMinutes(..., date, fromMinute: Int, toMinute: Int, mode): Int`。点列为空按第 5 节退化：外部列空则全周开阔、天花板列空则全周遮挡（界面据此禁用涉及天花板的档位）。
- 单测：`SolarTest.kt`（对拍第二套独立实现的低精度日下点公式与物理合理性断言：北半球正午方位约 180、夏至正午高度角约 90-lat+23.4、春秋分日出方位约 90、赤道昼长约 12h07m、南半球正午方位约 0）、`SkylineTest.kt`（插值、空隙三段、重叠取最大、环绕 350→10、覆盖度）、`SkylineShapeTest.kt`（`direct` 的线性插值与跨正北短弧、`viaHorizon` 的三段形状与端点原样保留、两条数组等长的校验；`SkylineShape` 此前零覆盖，而三处几何一旦对不上，看到的就是「屏幕上的覆盖与实际算出来的不一样」）、`SunlightTest.kt`（三档模式语义、空隙穿透、极夜零分钟、南半球镜像）。

`:app` 文件清单（包 `io.github.hecate2.D7` 下）：

- `data/Model.kt`：`@Serializable PointRecord(az, el, gapAfter, photoUri: String?, takenAt: Long)`、`@Serializable GroupRecord(id, name, lat, lon, altitude, zoneId, createdAt, updatedAt, external: MutableList<PointRecord>, ceiling: MutableList<PointRecord>)`、`@Serializable Store(version = 1, groups)`；`GroupRepository`（单例，`filesDir/groups.json` 单线程后台异步原子写、原子改名失败回退普通覆盖，`StateFlow<List<GroupRecord>>`，CRUD、`photoFolderFor()`〔同名组的相册文件夹消歧〕、`updatePointAngles()` 与 `setRegionPoints()`）。
- `sensor/OrientationSensor.kt`：注册旋转矢量，输出 `Pose(forward, right, up: FloatArray, frontAzDeg, frontElDeg, smoothAzDeg, smoothElDeg, rollDeg: Double, accuracy: Int)`；后摄视轴 `-col2(R)`，显示旋转到屏幕右/上向量的映射四种取值，滚转角 `atan2(-right[2], up[2])`，磁偏角经 `GeomagneticField` 叠加（绕世界 z 轴旋转三个基向量）。`sensor/LocationProvider.kt`：系统融合定位（多 provider 并发、`warmUp()` 预热与 `shutdown()` 注销〔由照片组管理页 `onStart`/`onStop` 驱动〕、`lastFresh()` 新鲜缓存、`requestSingleUpdate()` 快慢三档返回），另提供静态 `declination()` 磁偏角计算（`GeomagneticField` 的球谐展开较贵，而采集页以传感器频率调用，故按坐标 + 30 秒有效期做了一层缓存）。
- `camera/PhotoStore.kt`：快门拍照 → cacheDir 临时文件 → ExifInterface 写 `TAG_USER_COMMENT`（JSON：az/el/zone/group/seq/gap）+ GPS → 发布 MediaStore 至 `Pictures/D7/<文件夹名>/`（文件夹名由 `photoFolderFor()` 给定，同名组带短 id 后缀；API 29+ IS_PENDING；API 28- 公共目录+扫描，扫描只 fire-and-forget、返回 URI 不等待回调）→ 返回 content URI。`camera/CameraController.kt`：CameraX 绑定，`focalPx` 计算（见第 9 节，`focal_mm × max(viewW/传感器转屏宽mm, viewH/传感器转屏高mm)`，含 FILL_CENTER 裁剪），失败退回 65 度水平视场假设（半视场角 32.5 度）。
- `view/ViewfinderOverlayView.kt`：叠加层（参考弧按赤纬采样小时角生成，投影公式 `screenX = cx + (x/z)·focalPx`，`z ≤ 0.01` 剔除并断线；另有地平线以下的地面层与天际线遮挡填充，两者只依赖拍摄点，逐帧投影；以及今日轨迹上的「当前太阳」圆盘，逐帧按 `Solar.position` 直算，见第 9 节）；`view/AimCrosshair.kt`（`AimCrosshair` 是准星画法，`AimCrosshairView` 是结果页大图上那一个，取景器与它共用同一份，见第 9 节）；`view/CoverageBarView.kt`；`view/DayTimelineView.kt`；`view/YearCurveView.kt`（导出参考图的极坐标天际线由 `export/Exporter.kt` 直接在 Canvas 上绘制）。
- `data/StoreTransfer.kt`：存档的编码、读取与解析（`encodeStore` / `readStoreText` / `decodeStore`，以及字节数、组数、总点数三个上限）。纯函数、不碰 `Context`，所以整套语义校验的用例都跑在 `./gradlew test` 里；解析失败返回 null 而不抛，调用方只需要知道「这份文件不能用」。
- `ui/capture/AimGuard.kt`：瞄准合法性与警告滞回的纯函数（`canCapture` / `shouldWarn` 及三个阈值常量），抽出来是为了能在 JVM 单测里锁死边界，不依赖 `Activity` 与传感器。
- `ui/capture/CompassCheck.kt`：罗盘可信度定档的纯函数，平台精度与静置漂移取较差的一档（`DRIFT_GOOD_DEG = 3`、`DRIFT_OK_DEG = 10`），两路都没依据时给 `Grade.UNKNOWN` 而不是假装「高」。之所以要加自己测的那一路：实测本机（vivo，联发科方案）的 HAL 对旋转矢量恒定上报「高」，只信平台精度的话校准药丸等于没有信息。
- `ui/Grade.kt`：四态分级 `Grade`（好 / 中 / 差 / 未知）与取色的 `colorRes()`，沿用 `colors.xml` 的 `precision_*`。采集页的校准药丸与读数药丸、结果页的覆盖率提示都要「按档位给颜色与措辞」，收敛到一处之前每处都写两遍同样的 `when`，改一处漏一处。
- `:app` 的纯 JVM 单测（`app/src/test`，跑在 `:app:testDebugUnitTest`，不需要设备）：`ui/capture/AimGuardTest.kt`、`ui/capture/CompassCheckTest.kt`、`sensor/ScreenRotationTest.kt`、`data/GroupRepositoryConcurrencyTest.kt`、`data/GroupRepositoryStoreRecoveryTest.kt`、`data/StoreTransferTest.kt`。`GroupRepositoryConcurrencyTest` 用 `GroupRepository` 的 `internal` 构造函数直接传临时文件（不碰 `Context`），锁三件事：两线程各 150 次 `appendPoint` 后 300 个点一个不少（去掉 `stateLock` 即失败）、两线程各建 40 组后 80 组一个不少、删中间点后前后两点的 `gapAfter` 都要被清掉；另含一条落盘回读（写队列是异步的，故轮询到磁盘追上内存态再断言）。仪器测试里另有一份同名用例走真机文件系统，两条各有分工。
- `data/StoreTransferTest.kt`（17 例）：覆盖编码往返一致、导出物与落盘同源，以及每一条必须被整份拒绝的坏数据（缺键、非有限数、时区解析不了、经纬度与海拔越界、空 id、重复 id、规模超限、顶层不是对象），另锁 BOM 剥离与字节上限。用例构造坏数据的方式是「先把合法存档编码出来，再替换掉其中一段」，所以同一份文本既是正常路径的输入也是异常路径的输入，不会出现「测试自己的样本早就不是生产格式」这种漂移。
- `data/GroupRepositoryStoreRecoveryTest.kt`：存档读不动时的兜底。四种坏文件（缺必填键、数字写成非数字、JSON 被截断、含 `NaN`）逐个验三件事，留档内容与原文一致、主路径让开、之后仍能正常写新数据；另锁三条边界：没有存档时不凭空留档、正常存档原封不动、连坏两次要留两份而不是互相覆盖。纯 JVM，秒级。
- `GroupsStoreTransferTest.kt`（仪器，5 例）：导出/导入的端到端。用 espresso-intents 把系统文件选择器拦下来，返回一个自造的 MediaStore 下载 URI，于是发 Intent、读写、替换这整条链路都被真实执行一遍，只有「用户点了哪个文件」是假的。那个 URI 不能是 `file://`：`ContentResolver` 只允许 file 方案**读**，写会直接抛 `FileNotFoundException`。用例里那条「空库导出不许弹选择器」靠 `intended(..., times(0))` 断言，比睡一觉再看文件空不空可靠。
- `ui/groups/GroupsActivity.kt`（卡片列表用 LinearLayout 逐张 inflate、建组对话框=预填组名+经纬度+GPS 药丸〔打开即自动定位、光标与键盘落在组名上〕、长按编辑/删除（可勾选连带删照片）/导出、右上角语言键与常亮键）、`ui/GroupEditor.kt`（`showGroupEditor(activity, group)`：列表页长按与结果页两处共用的组编辑对话框，组名/经纬度/时区一起改，校验与文案只写一遍）、`ui/CoordinateFields.kt`（`EditText.bindHemisphereSuffix(suffix, of)`：经纬度两行右侧的 `°E`/`°W`、`°N`/`°S` 跟着输入正负逐字符更新）、`ui/capture/CaptureActivity.kt`（布局自上而下：标题栏、大小读数+`+180°` 药丸、取景器+chip+覆盖条+显示线、方向提示、左上角返回键、底部完成/手电筒/快门/对焦/删除五键；快门、删除键、对焦键共用 `bindPressGesture` 一份「按下计时、按住满 450 毫秒就地触发长按、滑出即取消、抬手不补记」的骨架，各自的触发内容与准入检查作为参数传入——快门满阈值即记地平线点并给按住反馈（准星下方提示 + 准星进度环 + 快门转圈），天花板区长按给提示不拍照；删除键的准入检查是「右侧有没有可删的点」，没有就只给一句提示、本次不参与手势，短按则弹一句「长按删右侧最近点」）、`ui/result/ResultActivity.kt`（左上角返回键、日期药丸、三档、主卡、时间线、国标卡、全年曲线、点列区（删点、连线模式切换、单点与批量角度编辑）、导出 CSV/图片、回采集续拍）、`ui/Settings.kt`（`CaptureLine` 枚举承载十条显示线的文案、线色、持久化键与默认值，`Settings` 按条目读写 SharedPreferences，并管 +180°、不拍照、屏幕常亮三项；三个页面共用这一个入口）。
- `ui/result/PointRows.kt`：结果页点列的行渲染（LinearLayout 逐行 `addView`，不做回收复用），每行是分区、序号、缩略图、方位与仰角、与左邻点的连线模式、删除按钮；缩略图按 URI 缓存于 `LruCache`，避免每次刷新都重新解码 JPEG。
- 资源：`res/values/colors.xml`（第 9 节配色，含 `ink #000000`、`card #161618`、`moon #CAC2D1`、`smoke #8E8E93`、`stroke #2E2E32`、`winter #378ADD`、`equinox #E24B4A`、`summer #EF9F27`、`sun #FFC107`）、`res/values/themes.xml` 的原生 Material 暗色主题（`Theme.D7`，不引入 AppCompat 与 Material 支持库）、图标由根目录 `城市日照十字瞄准图标.png` 生成自适应图标，另有 `ic_back`（返回箭头）、`ic_keep_on`（太阳，屏幕常亮）、`ic_zone_device`（手机，填本机时区）与 `ic_zone_longitude`（地球上的子午线，按经度推算）四枚手绘矢量，与 `ic_torch`/`ic_focus` 同一套线宽与圆头。工具：`util/Format.kt`（角度、坐标、时长格式化，以及计算档位名与分区名这两处曾各写一遍的枚举文案；坐标一律经度在前，与对话框里经度在上、纬度在下对齐；`latitudeSuffix`/`longitudeSuffix` 给两行经纬度的右侧那一格供词，与 `coordinate` 共用同一套半球记号）、`util/Zones.kt`（时区三选一的换算与解析，纯 `java.time`，见第 10 节）、`util/MediaFiles.kt`（组名清洗、删除照片、降采样解码，以及 `publishToPublicOrPrivate` 那套「MediaStore → 公共目录 + 扫描 → 应用私有目录」的三级落盘兜底；拍照与导出共用，`PublicDestination` 把同一处公共目录的两种说法绑在一起）、`util/Summaries.kt`（卡片与覆盖条派生数据）、`ui/PillStyle.kt`（药丸选中态着色，以及纯图标键的开关着色 `setToggleTint`，手电筒/对焦/常亮三处共用）、`ui/Extras.kt`（Activity 传参键）、`util/WindowFlags.kt`（`Activity.applyKeepScreenOn(on)`：三个页面都要用户举着手机盯画面，默认加 `FLAG_KEEP_SCREEN_ON`，用户在照片组页关掉时常量清除；该标志只在本窗口有焦点时生效，退到后台系统自己恢复，不必在 `onPause`/`onResume` 里配对清除）。

实施时按第 13 节顺序提交，每阶段跑 `:core:test` 与 `:app:assembleDebug` 作为门槛；自动化验证在模拟器 AVD 7d_api30（android-30）上运行 `:app:connectedDebugAndroidTest`，另有真机 vivo V2164PA（Android 11 / API 30）供人工实测。

## 附录 B：国际化（internationalization，缩写 i18n）设计

界面文案一律只从资源文件 `res/values/strings.xml` 读取，Kotlin 代码中不写死任何面向用户的中文文本；这条约定覆盖的不只是布局里的标签，还包括 Toast 提示、导出逗号分隔值（comma-separated values，缩写 CSV）文件的表头与字段名、导出参考图 PNG 内绘制的文字、自绘视图画布上的刻度与提示文字。中文是默认资源（`values/` 目录），因此新增文案先以中文写进默认资源；格式化型文案（时长、日期档位名、方位刻度等）同样进资源，用带位置参数（如 `%1$s`、`%2$02d`）的格式串在代码侧填充，避免在代码里拼接语序。

第二语言的落地流程是纯机械的：新建 `res/values-<语言>/strings.xml`，补齐需要翻译的条目即可，未翻译的条目自动回退到中文默认值。当前已落地 8 种：中文（`values/`）、英、日、韩、德、法、西、俄。语言名一律用 endonym（各自语言自己的写法，如 `Deutsch`），全部标 `translatable="false"` 只存一份——翻译成中文的「德语」对想切德语的用户毫无帮助，反而多一份要维护的文案。

个别条目刻意不给译文。`result_gb_hint` 是一条例外：它讲的是中国大陆的国标 GB 50180-2018，气候区划与有效时间带出了国境没有对照意义，八种语言里只有中文给出正文，其余七种在各自文件里写空串并附一句英文注释说明这是有意为之，免得后人当成漏译补上。空串是合法资源，`tools/check-locale-parity.py` 只比键集、占位符与换行数，不要求值非空；但空值不自动等于不占位，布局里那个 `TextView` 仍会量出那一行的高度与间距，所以界面侧要显式收起（见第 6 节）。

另一类不给译文的是**数值样例与通用记号**，它们在八种语言里本来就该一模一样，标 `translatable="false"` 只存一份。当前有七个：三个输入框的占位符样例 `label_latitude_hint`（`31.23`）、`label_longitude_hint`（`121.47`）、`label_timezone_hint`（`+05:30`），两行左侧的轴名 `label_lon`（`LON`）与 `label_lat`（`LAT`），时区填错时弹出的 `zone_invalid`（`Asia/Kolkata · +05:30`），以及定位按钮上的 `use_gps`（`GPS`）。都是纯数字、轴名或不分语种的缩写。它们不参与 `values-<语言>/` 那一圈翻译，也不进 `check-locale-parity.py` 的键集比较（该脚本跳过 `translatable="false"`），增删改只动 `values/strings.xml`。时区那一行因此在本 app 里没有一个需要翻译的字：输入框用样例值当占位符，两个快捷键只画图标，图标说不清会填成什么就把「按下去写进输入框的值」放进 `contentDescription`，读屏照样能报出具体时区。有文案含义的照常翻译，`group_name_hint`（「组名」/「Name」/「名前」…）与 `group_name_default`（预填的组名，「客厅阳台」/「Living-room balcony」…）各写八份。

界面语言的选择存 `SharedPreferences("locale")` 的 `tag` 键，空串表示跟随系统；核心逻辑在 `util/Locales.kt`。实现刻意**不引入 AppCompat**——`AppCompatDelegate.setApplicationLocales` 虽是省事的一条路，但等于为一件小事把 appcompat + fragment + material 全拖回来，与第 12 节「依赖树收到最小」的约定冲突。所以走最朴素可靠的一套：三个 Activity 各自在 `attachBaseContext` 里用 `createConfigurationContext` 覆写一份配置，minSdk 26 到 targetSdk 35 行为一致，也不必把用户领去系统设置里找。`Locale.setDefault` 必须跟着一起改，否则日期与数字（`String.format`）仍按默认语言拼，中文界面下会出现「12月21日」这种混搭。

语言较多时，**布局不能靠自适应**：LinearLayout 的横向行放不下时不报错，只会把带 weight 的那一格挤扁或让药丸折成两行——都不报错，界面已经坏了。所以 `LanguageSwitchTest.longTranslationsDoNotBreakCaptureLayout` 在德／俄／法三种字最宽的语言下直接量像素：读数行必须还剩宽度、药丸仍是单行、底部按键未被压没。7 份译文的键集、占位符与换行数由 `tools/check-locale-parity.py` 把关（键缺失、占位符编号错位、误用非法转换符都会在这里挂住）。

系统级「按应用设置语言」入口由两部分提供：`res/xml/locales_config.xml` 声明支持的语言清单（当前 8 种），`AndroidManifest.xml` 的 `android:localeConfig` 属性把它挂到应用上，Android 13 及以上即可在系统设置中为应用单独选语言。**新增语言目录时必须同步把该语言加进 `locales_config.xml`、`resourceConfigurations` 与 `Locales.TAGS`，且补齐条目后才算完成**：反过来，只声明不翻译会让用户在系统里选中该语言却拿到中文界面，而 lint 的 `MissingTranslation`（本工程已开启 `abortOnError`，属 error 级）正是用来拦住这种半成品的。

**明确不做的部分**（都在上面交代过理由，这里收拢成一份，免得再看见「以后要不要加」的讨论）：不引入 AppCompat 及其带来的 `AppCompatDelegate.setApplicationLocales`；不做导出内容的跨语言逐字节一致——导出文件名与 CSV 表头随当前界面语言变化，同一组数据在两种语言下导出的文件不同名也不同表头，这是刻意接受的；不为格式化型文案在代码里拼语序，一律走带位置参数的资源串。以 `Format` 工具类为例，时长、计算档位名与分区名都接收 `Resources` 后从字符串资源取词，在列表页、结果页与导出模块的三个调用场景共用同一套资源。唯一豁免是 `Format.coordinate` 拼接的半球符号 `N/E/S/W` 与度数符号 `°`：属于跨语言通用记号而非文案（模板为 `%.2f°%s %.2f°%s`，**经度在前**，与建组／编辑对话框里经度在上、纬度在下的顺序一致），不单列资源；若将来某语言需要不同写法，随该语言一并处理。