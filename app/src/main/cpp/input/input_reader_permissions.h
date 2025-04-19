#ifndef INPUT_READER_PERMISSIONS_H
#define INPUT_READER_PERMISSIONS_H

#include <string>

/**
 * @brief 通过 su 修复 /dev/input/event* 权限的尝试
 */
bool tryFixPermissions(const char* devicePath);

#endif // INPUT_READER_PERMISSIONS_H
