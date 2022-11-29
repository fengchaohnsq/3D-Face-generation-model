/*
 * Copyright University of Basel, Graphics and Vision Research Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
 package faces.apps

 import java.awt.Dimension
 import java.io.{File, IOException}
 import javax.swing._
 import javax.swing.event.{ChangeEvent, ChangeListener}
 import breeze.linalg.min
 import scalismo.color.{RGB, RGBA}
 import scalismo.faces.gui.{GUIBlock, GUIFrame, ImagePanel}
 import scalismo.faces.gui.GUIBlock._
 import scalismo.faces.parameters.RenderParameter
 import scalismo.faces.io.{MeshIO, MoMoIO, PixelImageIO, RenderParameterIO}
 import scalismo.faces.sampling.face.MoMoRenderer
 import scalismo.faces.image.PixelImage
 import scalismo.utils.Random
 import scalismo.faces.momo.MoMo

 import scala.math.Ordering.Double
 import scala.reflect.io.Path
 import scala.util.{Failure, Try}
 



object ModelViewer extends App {

  final val DEFAULT_DIR = new File(".")

  val modelFile: Option[File] = getModelFile(args.toIndexedSeq)
  modelFile.map(SimpleModelViewer(_))

  private def getModelFile(args: Seq[String]): Option[File] = {
    if (args.nonEmpty) {
      val path = Path(args.head)
      if (path.isFile) return Some(path.jfile)
      if (path.isDirectory) return askUserForModelFile(path.jfile)
    }
    askUserForModelFile(DEFAULT_DIR)
  }

  private def askUserForModelFile(dir: File): Option[File] = {
    val jFileChooser = new JFileChooser(dir)
    if (jFileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
      Some(jFileChooser.getSelectedFile)
    } else {
      println("No model select...")
      None
    }
  }
}

case class SimpleModelViewer(
  modelFile: File,
  imageWidth: Int = 512,
  imageHeight: Int = 512,
  maximalSliderValue: Int = 2,
  maximalShapeRank: Option[Int] = None,
  maximalColorRank: Option[Int] = None,
  maximalExpressionRank: Option[Int] = None
  ) {

  scalismo.initialize() //初始化scalismo 生成随机数
  val seed = 1024L
  implicit val rnd: Random = Random(seed)
  
  val model: MoMo = MoMoIO.read(modelFile, "").get //读取模型文件
  var showExpressionModel: Boolean = model.hasExpressions // 判断是否有表情参数
 
  val shapeRank: Int = maximalShapeRank match {
    case Some(rank) => min(model.neutralModel.shape.rank, rank)
    case _ => model.neutralModel.shape.rank
  }// 对shape参数进行排序

  val colorRank: Int = maximalColorRank match {
    case Some(rank) => min(model.neutralModel.color.rank, rank)
    case _ => model.neutralModel.color.rank
  }// 对color参数进行排序

  val expRank: Int = maximalExpressionRank match {
    case Some(rank) => try{min(model.expressionModel.get.expression.rank, rank)} catch {case _: Exception => 0}
    case _ => try{model.expressionModel.get.expression.rank} catch {case _: Exception => 0}
  }//对expression参数进行排序

  var renderer: MoMoRenderer = MoMoRenderer(model, RGBA.BlackTransparent).cached(5) // 渲染模型 设置缓存类型 instancer 

  val initDefault: RenderParameter = RenderParameter.defaultSquare.fitToImageSize(imageWidth, imageHeight) //设置渲染参数 图片的长宽
  val init10: RenderParameter = initDefault.copy(
    momo = initDefault.momo.withNumberOfCoefficients(shapeRank, colorRank, expRank)
  )// 初始化 渲染参数的shape color expression
  var init: RenderParameter = init10

  var changingSliders = false // 改变滑动条 设置为false

  val sliderSteps = 1000 // 滑动条步长
  var maximalSigma: Int = maximalSliderValue // 最大滑动条值 默认设置为2
  var maximalSigmaSpinner: JSpinner = {
    val spinner = new JSpinner(new SpinnerNumberModel(maximalSigma,0,999,1))
    spinner.addChangeListener( new ChangeListener() {
      override def stateChanged(e: ChangeEvent): Unit = {
        val newMaxSigma = spinner.getModel.asInstanceOf[SpinnerNumberModel].getNumber.intValue()
        maximalSigma = math.abs(newMaxSigma)
        setShapeSliders()
        setColorSliders()
        setExpSliders()
      }
    })
    spinner.setToolTipText("maximal slider value")
    spinner
  } // 设置上下微调器 用于控制滑块调整大小


  def sliderToParam(value: Int): Double = {
    maximalSigma * value.toDouble/sliderSteps
  } // 滑块滑动长度转化为参数

  def paramToSlider(value: Double): Int = {
    (value / maximalSigma * sliderSteps).toInt
  }// 参数转换为滑块滑动长度

  val bg: PixelImage[RGBA] = PixelImage(imageWidth, imageHeight, (_, _) => RGBA.Black)// 通过坐标访问的像素图片

  val imageWindow: ImagePanel[RGB] = ImagePanel(renderWithBG(init))//初始化显示面板

  //--- SHAPE -----
  val shapeSlider: IndexedSeq[JSlider] = for (n <- 0 until shapeRank) yield {
    GUIBlock.slider(-sliderSteps, sliderSteps, 0, f => {
      updateShape(n, f)
      updateImage()
    })
  } // 初始化控制shape每个参数维度的滑块 滑动滑块会自动更新shape维度信息和图片

  val shapeSliderView: JPanel = GUIBlock.shelf(shapeSlider.zipWithIndex.map(s => GUIBlock.stack(s._1, new JLabel("" + s._2))): _*) // 定义shape模块的普通面板
  val shapeScrollPane = new JScrollPane(shapeSliderView) // 定义shape模块的滚动条面板
  val shapeScrollBar: JScrollBar = shapeScrollPane.createVerticalScrollBar() // 定义shape模块界面横向滚动条
  shapeScrollPane.setSize(800, 300) 
  shapeScrollPane.setPreferredSize(new Dimension(800, 300)) // 定义shape模块滚动条面板初始长宽及偏好长宽

//shape模块的button
  // val rndShapeButton: JButton = GUIBlock.button("random", {
  //   randomShape(); updateImage()
  // }) // 定义shape模块 随机button 生成随机shape维度，并自动更新图片
  // rndShapeButton.setToolTipText("draw each shape parameter at random from a standard normal distribution")
  // val resetShapeButton: JButton = GUIBlock.button("reset", {
  //   resetShape(); updateImage()
  // })//定义shape模块 重置button 生成重置shape维度，并自动更新图片
  // resetShapeButton.setToolTipText("set all shape parameters to zero") // 定义随机和重置button的提示文字
  
  //函数 更新shape模块的维度参数 n代表维度 f代表取值
  def updateShape(n: Int, value: Int): Unit = {
    init = init.copy(momo = init.momo.copy(shape = {
      val current = init.momo.shape
      current.zipWithIndex.map { case (v, i) => if (i == n) sliderToParam(value) else v } 
    }))
  }

  //函数 随机shape模块的维度
  // def randomShape(): Unit = {
  //   init = init.copy(momo = init.momo.copy(shape = {
  //     val current = init.momo.shape
  //     current.zipWithIndex.map {
  //       case (_, _) =>
  //         rnd.scalaRandom.nextGaussian
  //     }//改变维度取值
  //   }))
  //   setShapeSliders()
  // }

  def randomShape(startIndex: Int, endIndex: Int): Unit = {
    for (n <- startIndex to endIndex){
      init = init.copy(momo = init.momo.copy(shape = {
        val current = init.momo.shape
        current.zipWithIndex.map { case (v, i) => if (i == n ) rnd.scalaRandom.nextGaussian else v }//改变维度取值
      }))
    }
    setShapeSliders()
  }


  //函数 重置shape模块的维度
  def resetShape(): Unit = {
    init = init.copy(momo = init.momo.copy(
      shape = IndexedSeq.fill(shapeRank)(0.0)
    ))
    setShapeSliders()
  }

  //函数 设置Shape模块的维度滑块位置
  def setShapeSliders(): Unit = {
    changingSliders = true
    (0 until shapeRank).foreach(i => {
      shapeSlider(i).setValue(paramToSlider(init.momo.shape(i)))
    })
    changingSliders = false
  }

  //--- COLOR -----
  val colorSlider: IndexedSeq[JSlider] = for (n <- 0 until colorRank) yield {
    GUIBlock.slider(-sliderSteps, sliderSteps, 0, f => {
      updateColor(n, f)
      updateImage()
    })
  } // 初始化控制color每个参数维度的滑块 滑动滑块会自动更新color维度信息和图片

  val colorSliderView: JPanel = GUIBlock.shelf(colorSlider.zipWithIndex.map(s => GUIBlock.stack(s._1, new JLabel("" + s._2))): _*) // 定义color模块的普通面板
  val colorScrollPane = new JScrollPane(colorSliderView) // 定义color模块的滚动条面板
  val colorScrollBar: JScrollBar = colorScrollPane.createHorizontalScrollBar() // 定义color模块界面横向滚动条
  colorScrollPane.setSize(800, 300)
  colorScrollPane.setPreferredSize(new Dimension(800, 300)) // 定义color模块滚动条面板初始长宽及偏好长宽

// color模块的button
  // val rndColorButton: JButton = GUIBlock.button("random", {
  //   randomColor(); updateImage()
  // }) // 定义color模块 随机button 生成随机color维度，并自动更新图片
  // rndColorButton.setToolTipText("draw each color parameter at random from a standard normal distribution")
  // val resetColorButton: JButton = GUIBlock.button("reset", {
  //   resetColor(); updateImage()
  // }) // 定义color模块 重置button 生成重置color维度，并自动更新图片
  // resetColorButton.setToolTipText("set all color parameters to zero") // 定义随机和重置button的提示文字

  //函数 更新color模块的维度参数
  def updateColor(n: Int, value: Int): Unit = {
    init = init.copy(momo = init.momo.copy(color = {
      val current = init.momo.color
      current.zipWithIndex.map { case (v, i) => if (i == n) sliderToParam(value) else v }
    }))
  }

  //函数 随机color模块的维度
  // def randomColor(): Unit = {
  //   init = init.copy(momo = init.momo.copy(color = {
  //     val current = init.momo.color
  //     current.zipWithIndex.map {
  //       case (_, _) =>
  //         rnd.scalaRandom.nextGaussian
  //     }

  //   }))
  //   setColorSliders() //生成随机数值后将滑块自动更新到对应位置
  // }

  def randomColor(startIndex: Int, endIndex: Int): Unit = {
    for (n <- startIndex to endIndex){
      init = init.copy(momo = init.momo.copy(color = {
        val current = init.momo.color
        current.zipWithIndex.map { case (v, i) => if (i == n ) rnd.scalaRandom.nextGaussian else v }//改变维度取值
      }))
    }
    setColorSliders() 
  }

  //函数 重置color模块的维度
  def resetColor(): Unit = {
    init = init.copy(momo = init.momo.copy(
      color = IndexedSeq.fill(colorRank)(0.0)
    ))
    setColorSliders()
  }

  //函数 设置color模块的维度滑块位置
  def setColorSliders(): Unit = {
    changingSliders = true
    (0 until colorRank).foreach(i => {
      colorSlider(i).setValue(paramToSlider(init.momo.color(i)))
    })
    changingSliders = false
  }

  //--- EXPRESSION -----
  val expSlider: IndexedSeq[JSlider] = for (n <- 0 until expRank)yield {
    GUIBlock.slider(-sliderSteps, sliderSteps, 0, f => {
      updateExpression(n, f)
      updateImage()
    })
  } // 初始化控制expression模块每个参数维度的滑块 滑动滑块会自动更新expression模块维度信息和图片

  val expSliderView: JPanel = GUIBlock.shelf(expSlider.zipWithIndex.map(s => GUIBlock.stack(s._1, new JLabel("" + s._2))): _*) // 定义expression模块的普通面板
  val expScrollPane = new JScrollPane(expSliderView) // 定义expression模块的滚动条面板
  val expScrollBar: JScrollBar = expScrollPane.createVerticalScrollBar() // 定义expression模块界面横向滚动条
  expScrollPane.setSize(800, 300)
  expScrollPane.setPreferredSize(new Dimension(800, 300)) // 定义expression模块滚动条面板初始长宽及偏好长宽

//expression模块button
  // val rndExpButton: JButton = GUIBlock.button("random", {
  //   randomExpression(); updateImage()
  // }) // 定义expression模块 随机button 生成随机expression维度，并自动更新图片
  // rndExpButton.setToolTipText("draw each expression parameter at random from a standard normal distribution")
  // val resetExpButton: JButton = GUIBlock.button("reset", {
  //   resetExpression(); updateImage()
  // }) // 定义expression模块 重置button 生成重置expression维度，并自动更新图片
  // resetExpButton.setToolTipText("set all expression parameters to zero") // 定义随机和重置button的提示文字

  //函数 更新expression模块的维度参数
  def updateExpression(n: Int, value: Int): Unit = {
    init = init.copy(momo = init.momo.copy(expression = {
      val current = init.momo.expression
      current.zipWithIndex.map { case (v, i) => if (i == n) sliderToParam(value) else v }
    }))
  }

  //函数 随机expression模块的维度参数
  // def randomExpression(): Unit = {
  //   init = init.copy(momo = init.momo.copy(expression = {
  //     val current = init.momo.expression
  //     current.zipWithIndex.map {
  //       case (_, _) =>
  //         rnd.scalaRandom.nextGaussian
  //     }

  //   }))
  //   setExpSliders() //生成随机数值后将滑块自动更新到对应位置
  // }

  def randomExpression(startIndex: Int, endIndex: Int): Unit = {
    for (n <- startIndex to endIndex){
      init = init.copy(momo = init.momo.copy(expression = {
      val current = init.momo.expression
      current.zipWithIndex.map { case (v, i) => if (i == n) rnd.scalaRandom.nextGaussian else v }
      }))
    }
    setExpSliders() 
  }

  //函数 重置expression模块的维度
  def resetExpression(): Unit = {
    init = init.copy(momo = init.momo.copy(
      expression = IndexedSeq.fill(expRank)(0.0)
    ))
    setExpSliders()
  }

  //函数 设置expression模块的维度滑块位置
  def setExpSliders(): Unit = {
    changingSliders = true
    (0 until expRank).foreach(i => {
      expSlider(i).setValue(paramToSlider(init.momo.expression(i)))
    })
    changingSliders = false
  }



  //维度范围输入Spinner,维度选定radioButton
  //color 模块
  var colorDimNum: Int = 0
  var colorDimentionSpinner: JSpinner = {
    val spinner = new JSpinner(new SpinnerNumberModel(colorDimNum,0,198,1))
    spinner.addChangeListener(new ChangeListener(){
      override def stateChanged(e:ChangeEvent) : Unit = {
        colorDimNum = math.abs(spinner.getModel.asInstanceOf[SpinnerNumberModel].getNumber.intValue())-1//控制维度个数，从0开始
      }
    })
    spinner.setToolTipText("设置随机图片的color维度数量（从0开始）")
    spinner
  }// 设置color维度的数量，从零开始
  var colorLabel = new JLabel("color维度的数量：")
  var colorRadio = new JRadioButton()

  //shape 模块
  var shapeDimNum: Int = 0
  var shapeDimentionsSpinner: JSpinner = {
    val shapeSpinner = new JSpinner(new SpinnerNumberModel(shapeDimNum,0,198,1))
    shapeSpinner.addChangeListener( new ChangeListener() {
      override def stateChanged(e: ChangeEvent): Unit = {
        shapeDimNum = math.abs(shapeSpinner.getModel.asInstanceOf[SpinnerNumberModel].getNumber.intValue())-1//控制维度个数，从0开始
      }
    })
    shapeSpinner.setToolTipText("设置随机图片的shape维度数量（从0开始）")
    shapeSpinner
  } // 设置shape维度的数量，从零开始
  var shapeLabel= new JLabel ("shape维度的数量：")
  var shapeRadio = new JRadioButton()
  
  //expression 模块
  var expDimNum: Int = 0
  var expDimentionSpinner: JSpinner = {
    val spinner = new JSpinner(new SpinnerNumberModel(expDimNum,0,99,1))
    spinner.addChangeListener(new ChangeListener(){
      override def stateChanged(e:ChangeEvent) : Unit = {
        expDimNum = math.abs(spinner.getModel.asInstanceOf[SpinnerNumberModel].getNumber.intValue())-1 //控制维度个数，从0开始
      }
    })
    spinner.setToolTipText("设置随机图片的expression维度数量（从0开始）")
    spinner
  }// 设置color维度的数量，从零开始
  var expLabel = new JLabel("expression维度的数量：")
  var expRadio = new JRadioButton()
  
  
  //--- ALL TOGETHER -----
  val randomButton: JButton = GUIBlock.button("random", {
    if(shapeRadio.isSelected()){randomShape(0,shapeDimNum)}
    if(colorRadio.isSelected()){randomColor(0,colorDimNum)}
    if(expRadio.isSelected()){randomExpression(0,expDimNum)}
    updateImage()
  }) //组合三个维度的随机方法绑定到一个随机button
  val resetButton: JButton = GUIBlock.button("reset", {
    if(shapeRadio.isSelected()){resetShape()}
    if(colorRadio.isSelected()){resetColor()}
    if(expRadio.isSelected()){resetExpression()}
    updateImage()
  }) //组合三个维度的重置方法绑定到一个随机button

  val toggleExpressionButton: JButton = GUIBlock.button("expressions off", {
    if ( model.hasExpressions ) {
      if ( showExpressionModel ) renderer = MoMoRenderer(model.neutralModel, RGBA.BlackTransparent).cached(5)
      else renderer = MoMoRenderer(model, RGBA.BlackTransparent).cached(5)
      showExpressionModel = !showExpressionModel
      updateToggleExpressionButton()
      addRemoveExpressionTab()
      updateImage()
    }
  }) //定义expression模块的开关button

  def updateToggleExpressionButton(): Unit = {
    if ( showExpressionModel ) toggleExpressionButton.setText("expressions off")
    else toggleExpressionButton.setText("expressions on")
  }// 更新expression模块开关的button文字

  randomButton.setToolTipText("draw each model parameter at random from a standard normal distribution")
  resetButton.setToolTipText("set all model parameters to zero")
  toggleExpressionButton.setToolTipText("toggle expression part of model on and off") // 定义随机、重置以及expression模块开关button的提示文字


  //function to export the current shown face as a .ply file
  def exportShape (): Try[Unit] ={

    def askToOverwrite(file: File): Boolean = {
      val dialogButton = JOptionPane.YES_NO_OPTION
      JOptionPane.showConfirmDialog(null, s"Would you like to overwrite the existing file: $file?","Warning",dialogButton) == JOptionPane.YES_OPTION
    }

    // val VCM3D = if (model.hasExpressions && !showExpressionModel) model.neutralModel.instance(init.momo.coefficients)
    // else model.instance(init.momo.coefficients)

    val VCM3D =  model.instance(init.momo.coefficients)
    
    val fc = new JFileChooser()
    fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES)
    fc.setDialogTitle("Select a folder to store the .ply file and name it")
    if (fc.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
      var file = fc.getSelectedFile
      //var file = new File(fc.getSelectedFile,String.valueOf(indexNum)+".png") 编写多个PLY文件保存
      if (file.isDirectory) file = new File(file,"instance.ply")
      if ( !file.getName.endsWith(".ply")) file = new File( file.getAbsolutePath+".ply")
      if (!file.exists() || askToOverwrite(file)) {
        MeshIO.write(VCM3D, file)
      } else {
        Failure(new IOException(s"Something went wrong when writing to file the file $file."))
      }
    } else {
      Failure(new Exception("User aborted save dialog."))
    }
  }

  //exportShape button and its tooltip
  val exportPLYButton: JButton = GUIBlock.button("export PLY",
    {
      exportShape()
    }
  )// 定义输出ply文档的button
  exportPLYButton.setToolTipText("export the current shape and texture as .ply") // 定义输出ply文档的button的提示文字


  
  //输出脸孔图片模块

   //定义输出图片的JSpinner和JLabel
  var imgNum: Int = 1 // 输出批次图片的张数
  var exportSpinner: JSpinner = {
    val spinner = new JSpinner(new SpinnerNumberModel(imgNum,1,999999,1))
    spinner.addChangeListener( new ChangeListener() {
      override def stateChanged(e: ChangeEvent): Unit = {
         imgNum= math.abs(spinner.getModel.asInstanceOf[SpinnerNumberModel].getNumber.intValue())
      }
    })
    spinner.setToolTipText("设置生成图片的数量，默认为1")
    spinner
  } 
  var exportLabel= new JLabel ("输出图片数量：")

  //输出面孔图片函数
  //function to export the current shown face as a .PNG file
  // def exportImage (): Try[Unit] ={
  //   def askToOverwrite(file: File): Boolean = {
  //     val dialogButton = JOptionPane.YES_NO_OPTION
  //     JOptionPane.showConfirmDialog(null, s"Would you like to overwrite the existing file: $file?","Warning",dialogButton) == JOptionPane.YES_OPTION
  //   }
  //   val img = renderer.renderImage(init)
  //   val fc = new JFileChooser()
  //   fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES)
  //   fc.setDialogTitle("Select a folder to store the .png file and name it")
  //   if (fc.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
  //     var file = fc.getSelectedFile
  //     if (file.isDirectory) file = new File(file,"instance.png")
  //     if ( !file.getName.endsWith(".png")) file = new File( file.getAbsolutePath+".png")
  //     if (!file.exists() || askToOverwrite(file)) {
  //       PixelImageIO.write(img, file)
  //     } else {
  //       Failure(new IOException(s"Something went wrong when writing to file the file $file."))
  //     }
  //   } else {
  //     Failure(new Exception("User aborted save dialog."))
  //   }
  // }

  def exportBatchImages (exportNum: Int) = {
    def askToOverwrite(file: File): Boolean = {
      val dialogButton = JOptionPane.YES_NO_OPTION
      JOptionPane.showConfirmDialog(null, s"Would you like to overwrite the existing file: $file?","Warning",dialogButton) == JOptionPane.YES_OPTION
    }
    val fc = new JFileChooser()
    fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES)
    fc.setDialogTitle("Select a folder to store the .png file and name it")
    // var file = fc.getSelectedFile
    if (fc.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
      for(indexNum <- 1 to exportNum){
        if(shapeRadio.isSelected()){randomShape(0,shapeDimNum)}
        if(colorRadio.isSelected()){randomColor(0,colorDimNum)}
        if(expRadio.isSelected()){randomExpression(0,expDimNum)}
        var file = new File(fc.getSelectedFile,String.valueOf(indexNum)+".png")
        // println(file)
        val img = renderer.renderImage(init)
        PixelImageIO.write(img, file)
      }
    } else {
      Failure(new Exception("User aborted save dialog."))
    }
  }


  // 定义输出png图片的button
  //exportImage button and its tooltip
  val exportImageButton: JButton = GUIBlock.button("export PNG",
    {
      exportBatchImages(imgNum)
    }
  )
  exportImageButton.setToolTipText("export images as .png") // 定义输出png图片的button的提示文字 


  //面孔旋转控制模块
  //pitch模块
  var pitchLabel = new JLabel("pitch度数：")
  var pitchRadio = new JRadioButton()
  var pitchNum: Int = 0
  var pitchSpinner: JSpinner = {
    val spinner = new JSpinner(new SpinnerNumberModel(pitchNum,-180,180,1))
    spinner.addChangeListener(new ChangeListener(){
      override def stateChanged(e:ChangeEvent) : Unit = {
        pitchNum = spinner.getModel.asInstanceOf[SpinnerNumberModel].getNumber.intValue()
        if(pitchRadio.isSelected()){
          val pitchPose = math.Pi * pitchNum/180
          init = init.copy(pose = init.pose.copy(pitch = pitchPose))
          updateImage()
        }
      }
    })
    spinner.setToolTipText("设置绕X轴旋转角pitch（从0开始）")
    spinner
  }
  //yaw模块
  var yawLabel = new JLabel("yaw度数：")
  var yawRadio = new JRadioButton()
  var yawNum: Int = 0
  var yawSpinner: JSpinner = {
    val spinner = new JSpinner(new SpinnerNumberModel(yawNum,-180,180,1))
    spinner.addChangeListener(new ChangeListener(){
      override def stateChanged(e:ChangeEvent) : Unit = {
        yawNum = spinner.getModel.asInstanceOf[SpinnerNumberModel].getNumber.intValue()
        if(yawRadio.isSelected()){
          val yawPose = math.Pi * yawNum/180 
          init = init.copy(pose = init.pose.copy(yaw = yawPose))
          updateImage()
        }
      }
    })
    spinner.setToolTipText("设置绕Y轴旋转角yaw（从0开始）")
    spinner
  }
  //roll模块
  var rollLabel = new JLabel("roll度数：")
  var rollRadio = new JRadioButton()
  var rollNum: Int = 0
  var rollSpinner: JSpinner = {
    val spinner = new JSpinner(new SpinnerNumberModel(rollNum,-180,180,1))
    spinner.addChangeListener(new ChangeListener(){
      override def stateChanged(e:ChangeEvent) : Unit = {
        rollNum = spinner.getModel.asInstanceOf[SpinnerNumberModel].getNumber.intValue()
        if(rollRadio.isSelected()){
          val rollPose = math.Pi * rollNum/180 
          init = init.copy(pose = init.pose.copy(roll = rollPose))
          updateImage()
        }
      }
    })
    spinner.setToolTipText("设置绕z轴旋转角roll（从0开始）")
    spinner
  }

  //export RPS
  //write file to rps
  def exportRPSfile () = {
    def askToOverwrite(file: File): Boolean = {
      val dialogButton = JOptionPane.YES_NO_OPTION
      JOptionPane.showConfirmDialog(null, s"Would you like to overwrite the existing file: $file?","Warning",dialogButton) == JOptionPane.YES_OPTION
    }
    val fc = new JFileChooser()
    fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES)
    fc.setDialogTitle("Select a folder to store the .png file and name it")
    // var file = fc.getSelectedFile
    if (fc.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
      RenderParameterIO.write(init,new File(fc.getSelectedFile,"face.rps"))
    } else {
      Failure(new Exception("User aborted save dialog."))
    }
  }

  val exportRPSButton: JButton = GUIBlock.button("export RPS",{exportRPSfile ()})


  //loads parameters from file
  //TODO: load other parameters than the momo shape, expr and color
  def askUserForRPSFile(dir: File): Option[File] = {
    val jFileChooser = new JFileChooser(dir)
    if (jFileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
      Some(jFileChooser.getSelectedFile)
    } else {
      println("No Parameters select...")
      None
    }
  }

  def resizeParameterSequence(params: IndexedSeq[Double], length: Int, fill: Double): IndexedSeq[Double] = {
    val zeros = IndexedSeq.fill[Double](length)(fill)
    (params ++ zeros).slice(0, length) //brute force
  }

  def updateModelParameters(params: RenderParameter): Unit = {
    val newShape = resizeParameterSequence(params.momo.shape, shapeRank, 0)
    val newColor = resizeParameterSequence(params.momo.color, colorRank, 0)
    val newExpr = resizeParameterSequence(params.momo.expression, expRank, 0)
    println("Loaded Parameters")

    init = init.copy(momo = init.momo.copy(shape = newShape, color = newColor, expression = newExpr))
    setShapeSliders()
    setColorSliders()
    setExpSliders()
    updateImage()
  }

  val loadButton: JButton = GUIBlock.button(
    "load RPS",
    {
      for {rpsFile <- askUserForRPSFile(new File("."))
           rpsParams <- RenderParameterIO.read(rpsFile)} {
        implicit val order: Double.TotalOrdering.type = Ordering.Double.TotalOrdering
        val maxSigma = (rpsParams.momo.shape ++ rpsParams.momo.color ++ rpsParams.momo.expression).map(math.abs).max
        if ( maxSigma > maximalSigma ) {
          maximalSigma = math.ceil(maxSigma).toInt
          maximalSigmaSpinner.setValue(maximalSigma)
          setShapeSliders()
          setColorSliders()
          setExpSliders()
        }
        updateModelParameters(rpsParams)
      }
    }
  )


  //---- update the image
  def updateImage(): Unit = {
    if (!changingSliders)
      imageWindow.updateImage(renderWithBG(init))
  }

  def renderWithBG(init: RenderParameter): PixelImage[RGB] = {
    val fg = renderer.renderImage(init)
    fg.zip(bg).map { case (f, b) => b.toRGB.blend(f) }
    //    fg.map(_.toRGB)
  }

  //--- COMPOSE FRAME ------
  // 将所有的控件组合排版
  val controls = new JTabbedPane()
  controls.addTab("color", GUIBlock.stack(colorScrollPane))
  controls.addTab("shape", GUIBlock.stack(shapeScrollPane))
  // controls.addTab("color", GUIBlock.stack(colorScrollPane, GUIBlock.shelf( resetColorButton)))
  // controls.addTab("shape", GUIBlock.stack(shapeScrollPane, GUIBlock.shelf( resetShapeButton)))
  if ( model.hasExpressions)
    controls.addTab("expression", GUIBlock.stack(expScrollPane))
    // controls.addTab("expression", GUIBlock.stack(expScrollPane, GUIBlock.shelf(resetExpButton)))
  def addRemoveExpressionTab(): Unit = {
    if ( showExpressionModel ) {
      controls.addTab("expression", GUIBlock.stack(expScrollPane))
      // controls.addTab("expression", GUIBlock.stack(expScrollPane, GUIBlock.shelf(rndExpButton, resetExpButton)))
    } else {
      val idx = controls.indexOfTab("expression")
      if ( idx >= 0) controls.remove(idx)
    }
  }

  val guiFrame: GUIFrame = GUIBlock.stack(
    GUIBlock.shelf(imageWindow,
      GUIBlock.stack(controls,
        if (model.hasExpressions) {
          GUIBlock.shelf(colorRadio, colorLabel, colorDimentionSpinner, shapeRadio, shapeLabel, shapeDimentionsSpinner, expRadio, expLabel, expDimentionSpinner, randomButton, resetButton, pitchRadio, pitchLabel, pitchSpinner,yawRadio, yawLabel, yawSpinner,rollRadio, rollLabel, rollSpinner, toggleExpressionButton, 
          exportLabel, exportSpinner, exportImageButton, loadButton,exportRPSButton)
        } else {
          GUIBlock.shelf(maximalSigmaSpinner, randomButton, resetButton, exportImageButton)
        }
      )
    )
  ).displayIn("MoMo-Viewer")


//   //--- ROTATION CONTROLS ------

//   // import java.awt.event._

//   // var lookAt = false
//   // imageWindow.requestFocusInWindow()

//   // imageWindow.addKeyListener(new KeyListener {
//   //   override def keyTyped(e: KeyEvent): Unit = {
//   //   }

//   //   override def keyPressed(e: KeyEvent): Unit = {
//   //     if (e.getKeyCode == KeyEvent.VK_CONTROL) lookAt = true
//   //   }

//   //   override def keyReleased(e: KeyEvent): Unit = {
//   //     if (e.getKeyCode == KeyEvent.VK_CONTROL) lookAt = false
//   //   }
//   // })

//   // imageWindow.addMouseListener(new MouseListener {
//   //   override def mouseExited(e: MouseEvent): Unit = {}

//   //   override def mouseClicked(e: MouseEvent): Unit = {
//   //     imageWindow.requestFocusInWindow()
//   //   }

//   //   override def mouseEntered(e: MouseEvent): Unit = {}

//   //   override def mousePressed(e: MouseEvent): Unit = {}

//   //   override def mouseReleased(e: MouseEvent): Unit = {}
//   // })

//   // imageWindow.addMouseMotionListener(new MouseMotionListener {
//   //   override def mouseMoved(e: MouseEvent): Unit = {
//   //     if (lookAt) {
//   //       val x = e.getX
//   //       val y = e.getY
//   //       val yawPose = math.Pi / 2 * (x - imageWidth * 0.5) / (imageWidth / 2)
//   //       val pitchPose = math.Pi / 2 * (y - imageHeight * 0.5) / (imageHeight / 2)

//   //       init = init.copy(pose = init.pose.copy(yaw = yawPose, pitch = pitchPose))
//   //       updateImage()
//   //     }
//   //   }

//   //   override def mouseDragged(e: MouseEvent): Unit = {}
//   // })

}
