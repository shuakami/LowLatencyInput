#ifndef INPUT_READER_LOOP_H
#define INPUT_READER_LOOP_H

// 确保能识别 callSendUiEventPacketJNI 等
#include "input_reader_jni_utils.h"

/**
 * @brief 输入读取线程主循环函数
 * @param devicePath 设备路径，例如 "/dev/input/event4"
 */
void inputReaderLoop(const char* devicePath);

#endif // INPUT_READER_LOOP_H
