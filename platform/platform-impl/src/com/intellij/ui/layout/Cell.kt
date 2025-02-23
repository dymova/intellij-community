// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ui.layout

import com.intellij.BundleBase
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.UINumericRange
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.*
import com.intellij.ui.components.*
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.MouseEvent
import javax.swing.*
import kotlin.jvm.internal.CallableReference
import kotlin.reflect.KMutableProperty0

@DslMarker
annotation class CellMarker

data class PropertyBinding<V>(val get: () -> V, val set: (V) -> Unit)

@PublishedApi
internal fun <T> createPropertyBinding(prop: KMutableProperty0<T>, propType: Class<T>): PropertyBinding<T> {
  if (prop is CallableReference) {
    val name = prop.name
    val receiver = (prop as CallableReference).boundReceiver
    if (receiver != null) {
      val baseName = name.removePrefix("is")
      val nameCapitalized = StringUtil.capitalize(baseName)
      val getterName = if (name.startsWith("is")) name else "get$nameCapitalized"
      val setterName = "set$nameCapitalized"
      val receiverClass = receiver::class.java

      try {
        val getter = receiverClass.getMethod(getterName)
        val setter = receiverClass.getMethod(setterName, propType)
        return PropertyBinding({ getter.invoke(receiver) as T }, { setter.invoke(receiver, it) })
      }
      catch (e: Exception) {
        // ignore
      }
    }
  }
  return PropertyBinding(prop.getter, prop.setter)
}

fun <T> PropertyBinding<T>.toNullable(): PropertyBinding<T?> {
  return PropertyBinding<T?>({ get() }, { set(it!!) })
}

inline fun <reified T : Any> KMutableProperty0<T>.toBinding(): PropertyBinding<T> {
  return createPropertyBinding(this, T::class.javaPrimitiveType ?: T::class.java)
}

interface CellBuilder<T : JComponent> {
  val component: T

  fun focused(): CellBuilder<T>
  fun withValidation(callback: (T) -> ValidationInfo?): CellBuilder<T>
  fun onApply(callback: () -> Unit): CellBuilder<T>
  fun onReset(callback: () -> Unit): CellBuilder<T>
  fun onIsModified(callback: () -> Boolean): CellBuilder<T>

  fun <V> withBinding(
    componentGet: (T) -> V,
    componentSet: (T, V) -> Unit,
    modelBinding: PropertyBinding<V>
  ): CellBuilder<T> {
    onApply { modelBinding.set(componentGet(component)) }
    onReset { componentSet(component, modelBinding.get()) }
    onIsModified { componentGet(component) != modelBinding.get() }
    return this
  }

  fun enabled(isEnabled: Boolean)
  fun enableIf(predicate: ComponentPredicate): CellBuilder<T>

  fun withErrorIf(message: String, callback: (T) -> Boolean): CellBuilder<T> {
    withValidation { if (callback(it)) ValidationInfo(message, it) else null }
    return this
  }
}

internal interface CheckboxCellBuilder {
  fun actsAsLabel()
}

fun <T : JCheckBox> CellBuilder<T>.actsAsLabel(): CellBuilder<T> {
  (this as CheckboxCellBuilder).actsAsLabel()
  return this
}

fun <T : JTextField> CellBuilder<T>.withTextBinding(modelBinding: PropertyBinding<String>): CellBuilder<T> {
  return withBinding(JTextField::getText, JTextField::setText, modelBinding)
}

fun <T : AbstractButton> CellBuilder<T>.withSelectedBinding(modelBinding: PropertyBinding<Boolean>): CellBuilder<T> {
  return withBinding(AbstractButton::isSelected, AbstractButton::setSelected, modelBinding)
}

val CellBuilder<out AbstractButton>.selected
  get() = component.selected

const val UNBOUND_RADIO_BUTTON = "unbound.radio.button"

// separate class to avoid row related methods in the `cell { } `
@CellMarker
abstract class Cell {
  /**
   * Sets how keen the component should be to grow in relation to other component **in the same cell**. Use `push` in addition if need.
   * If this constraint is not set the grow weight is set to 0 and the component will not grow (unless some automatic rule is not applied (see [com.intellij.ui.layout.panel])).
   * Grow weight will only be compared against the weights for the same cell.
   */
  val growX = CCFlags.growX
  @Suppress("unused")
  val growY = CCFlags.growY
  val grow = CCFlags.grow

  /**
   * Makes the column that the component is residing in grow with `weight`.
   */
  val pushX = CCFlags.pushX

  /**
   * Makes the row that the component is residing in grow with `weight`.
   */
  @Suppress("unused")
  val pushY = CCFlags.pushY
  val push = CCFlags.push

  // backward compatibility - return type should be void
  fun label(text: String, gapLeft: Int = 0, style: UIUtil.ComponentStyle? = null, fontColor: UIUtil.FontColor? = null, bold: Boolean = false) {
    val label = Label(text, style, fontColor, bold)
    label(gapLeft = gapLeft)
  }

  fun link(text: String, style: UIUtil.ComponentStyle? = null, action: () -> Unit) {
    val result = Link(text, action = action)
    style?.let { UIUtil.applyStyle(it, result) }
    result()
  }

  fun browserLink(text: String, url: String) {
    val result = HyperlinkLabel()
    result.setHyperlinkText(text)
    result.setHyperlinkTarget(url)
    result()
  }

  fun button(text: String, vararg constraints: CCFlags, actionListener: (event: ActionEvent) -> Unit) {
    val button = JButton(BundleBase.replaceMnemonicAmpersand(text))
    button.addActionListener(actionListener)
    button(*constraints)
  }

  inline fun checkBox(text: String,
                      isSelected: Boolean = false,
                      comment: String? = null,
                      vararg constraints: CCFlags,
                      crossinline actionListener: (event: ActionEvent, component: JCheckBox) -> Unit): JCheckBox {
    val component = checkBox(text, isSelected, comment, *constraints)
    component.addActionListener(ActionListener {
      actionListener(it, component)
    })
    return component
  }

  @JvmOverloads
  fun checkBox(text: String, isSelected: Boolean = false, comment: String? = null, vararg constraints: CCFlags = emptyArray()): JCheckBox {
    val component = JCheckBox(text)
    component.isSelected = isSelected
    component(*constraints, comment = comment)
    return component
  }

  fun checkBox(text: String, prop: KMutableProperty0<Boolean>, comment: String? = null): CellBuilder<JBCheckBox> {
    return checkBox(text, prop.toBinding(), comment)
  }

  fun checkBox(text: String, getter: () -> Boolean, setter: (Boolean) -> Unit, comment: String? = null): CellBuilder<JBCheckBox> {
    return checkBox(text, PropertyBinding(getter, setter), comment)
  }

  private fun checkBox(text: String,
                       modelBinding: PropertyBinding<Boolean>,
                       comment: String?): CellBuilder<JBCheckBox> {
    val component = JBCheckBox(text, modelBinding.get())
    return component(comment = comment).withSelectedBinding(modelBinding)
  }

  fun radioButton(text: String, comment: String? = null): CellBuilder<JBRadioButton> {
    val component = JBRadioButton(text)
    component.putClientProperty(UNBOUND_RADIO_BUTTON, true)
    return component(comment = comment)
  }

  fun radioButton(text: String, prop: KMutableProperty0<Boolean>, comment: String? = null): CellBuilder<JBRadioButton> {
    val component = JBRadioButton(text, prop.get())
    return component(comment = comment).withSelectedBinding(prop.toBinding())
  }

  fun <T> comboBox(model: ComboBoxModel<T>, getter: () -> T?, setter: (T?) -> Unit, growPolicy: GrowPolicy? = null, renderer: ListCellRenderer<T?>? = null
  ): CellBuilder<ComboBox<T>> {
    return comboBox(model, PropertyBinding(getter, setter), growPolicy, renderer)
  }

  fun <T> comboBox(model: ComboBoxModel<T>,
                   modelBinding: PropertyBinding<T?>,
                   growPolicy: GrowPolicy?,
                   renderer: ListCellRenderer<T?>?): CellBuilder<ComboBox<T>> {
    val component = ComboBox(model)
    if (renderer != null) {
      component.renderer = renderer
    }
    else {
      component.renderer = SimpleListCellRenderer.create("") { it.toString() }
    }
    val builder = component(growPolicy = growPolicy)
    return builder.withBinding(
      { component -> component.selectedItem as T? },
      { component, value -> component.setSelectedItem(value) },
      modelBinding
    )
  }

  inline fun <reified T : Any> comboBox(model: ComboBoxModel<T>, prop: KMutableProperty0<T>, growPolicy: GrowPolicy? = null, renderer: ListCellRenderer<T?>? = null): CellBuilder<ComboBox<T>> {
    return comboBox(model, prop.toBinding().toNullable(), growPolicy, renderer)
  }

  fun textField(prop: KMutableProperty0<String>, columns: Int? = null): CellBuilder<JTextField> {
    val component = JTextField(prop.get(),columns ?: 0)
    val builder = component()
    return builder.withTextBinding(prop.toBinding())
  }

  fun textField(getter: () -> String, setter: (String) -> Unit, columns: Int? = null): CellBuilder<JTextField> {
    val component = JTextField(getter(),columns ?: 0)
    val builder = component()
    return builder.withTextBinding(PropertyBinding(getter, setter))
  }

  fun intTextField(prop: KMutableProperty0<Int>, columns: Int? = null, range: UINumericRange? = null): CellBuilder<JTextField> {
    return textField(
      { prop.get().toString() },
      { value -> value.toIntOrNull()?.let { prop.set(range?.fit(it) ?: it) } },
      columns
    )
  }

  fun intTextField(getter: () -> Int, setter: (Int) -> Unit, columns: Int? = null, range: UINumericRange? = null): CellBuilder<JTextField> {
    return textField(
      { getter().toString() },
      { value -> value.toIntOrNull()?.let { setter(range?.fit(it) ?: it) } },
      columns
    )
  }

  fun spinner(prop: KMutableProperty0<Int>, minValue: Int, maxValue: Int, step: Int = 1): CellBuilder<JBIntSpinner> {
    val component = JBIntSpinner(prop.get(), minValue, maxValue, step)
    return component().withBinding(JBIntSpinner::getNumber, JBIntSpinner::setNumber, prop.toBinding())
  }

  fun spinner(getter: () -> Int, setter: (Int) -> Unit, minValue: Int, maxValue: Int, step: Int = 1): CellBuilder<JBIntSpinner> {
    val component = JBIntSpinner(getter(), minValue, maxValue, step)
    return component().withBinding(JBIntSpinner::getNumber, JBIntSpinner::setNumber, PropertyBinding(getter, setter))
  }

  fun textFieldWithHistoryWithBrowseButton(browseDialogTitle: String,
                                           value: String? = null,
                                           project: Project? = null,
                                           fileChooserDescriptor: FileChooserDescriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor(),
                                           historyProvider: (() -> List<String>)? = null,
                                           fileChosen: ((chosenFile: VirtualFile) -> String)? = null,
                                           comment: String? = null): TextFieldWithHistoryWithBrowseButton {
    val component = textFieldWithHistoryWithBrowseButton(project, browseDialogTitle, fileChooserDescriptor, historyProvider, fileChosen)
    value?.let { component.text = it }
    component(comment = comment)
    return component
  }

  fun textFieldWithBrowseButton(browseDialogTitle: String? = null,
                                value: String? = null,
                                project: Project? = null,
                                fileChooserDescriptor: FileChooserDescriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor(),
                                fileChosen: ((chosenFile: VirtualFile) -> String)? = null,
                                comment: String? = null): TextFieldWithBrowseButton {
    val component = textFieldWithBrowseButton(project, browseDialogTitle, fileChooserDescriptor, fileChosen)
    value?.let { component.text = it }
    component(comment = comment)
    return component
  }

  fun textFieldWithBrowseButton(
    prop: KMutableProperty0<String>,
    browseDialogTitle: String? = null,
    project: Project? = null,
    fileChooserDescriptor: FileChooserDescriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor(),
    fileChosen: ((chosenFile: VirtualFile) -> String)? = null,
    comment: String? = null
  ): CellBuilder<TextFieldWithBrowseButton> {
    val component = textFieldWithBrowseButton(project, browseDialogTitle, fileChooserDescriptor, fileChosen)
    component.text = prop.get()
    return component(comment = comment).withBinding(TextFieldWithBrowseButton::getText, TextFieldWithBrowseButton::setText, prop.toBinding())
  }

  fun textFieldWithBrowseButton(
    getter: () -> String,
    setter: (String) -> Unit,
    browseDialogTitle: String? = null,
    project: Project? = null,
    fileChooserDescriptor: FileChooserDescriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor(),
    fileChosen: ((chosenFile: VirtualFile) -> String)? = null,
    comment: String? = null
  ): CellBuilder<TextFieldWithBrowseButton> {
    val component = textFieldWithBrowseButton(project, browseDialogTitle, fileChooserDescriptor, fileChosen)
    component.text = getter()
    return component(comment = comment).withBinding(TextFieldWithBrowseButton::getText, TextFieldWithBrowseButton::setText, PropertyBinding(getter, setter))
  }

  fun gearButton(vararg actions: AnAction) {
    val label = JLabel(AllIcons.General.GearPlain)
    label.disabledIcon = AllIcons.General.GearPlain
    object : ClickListener() {
      override fun onClick(e: MouseEvent, clickCount: Int): Boolean {
        if (!label.isEnabled) return true
        JBPopupFactory.getInstance()
          .createActionGroupPopup(null, DefaultActionGroup(*actions), DataContext { dataId ->
            when (dataId) {
              PlatformDataKeys.CONTEXT_COMPONENT.name -> label
              else -> null
            }
          }, true, null, 10)
          .showUnderneathOf(label)
        return true
      }
    }.installOn(label)

    label()
  }

  /**
   * @see LayoutBuilder.titledRow
   */
  @JvmOverloads
  fun panel(title: String, wrappedComponent: Component, hasSeparator: Boolean = true, vararg constraints: CCFlags) {
    val panel = Panel(title, hasSeparator)
    panel.add(wrappedComponent)
    panel(*constraints)
  }

  fun scrollPane(component: Component, vararg constraints: CCFlags) {
    JBScrollPane(component)(*constraints)
  }

  abstract operator fun <T : JComponent> T.invoke(
    vararg constraints: CCFlags,
    gapLeft: Int = 0,
    growPolicy: GrowPolicy? = null,
    comment: String? = null
  ): CellBuilder<T>

}

fun <T> listCellRenderer(renderer: ColoredListCellRenderer<T?>.(value: T, index: Int, isSelected: Boolean) -> Unit): ColoredListCellRenderer<T?> {
  return object : ColoredListCellRenderer<T?>() {
    override fun customizeCellRenderer(list: JList<out T?>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) {
      if (value != null) {
        renderer(this, value, index, selected)
      }
    }
  }
}
