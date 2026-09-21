package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.SysMenu;
import org.familyhealthcare.service.SysMenuService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

import static org.familyhealthcare.controller.RoleController.isAdmin;

@RestController
@RequestMapping("/menu")
@Api(tags = "Menu Management")
public class MenuController {

    @Autowired
    private SysMenuService menuService;

    @GetMapping("/list")
    @ApiOperation("queryhas Menu")
    public Result<List<SysMenu>> list() {
        return Result.ok(menuService.getAllMenus());
    }

    @PostMapping("/save")
    @ApiOperation("Addor updateMenu")
    public Result<String> save(@RequestBody SysMenu menu, HttpServletRequest request) {
        if (!isAdmin(request)) {
            return Result.error(403, "onlymanagementmembercan maintainMenu");
        }
        boolean success = menuService.saveOrUpdate(menu);
        return success ? Result.ok("Saved successfully") : Result.error("Failed to save");
    }

    @DeleteMapping("/delete/{id}")
    @ApiOperation("DeleteMenu")
    public Result<String> delete(@PathVariable Long id, HttpServletRequest request) {
        if (!isAdmin(request)) {
            return Result.error(403, "onlymanagementmembercan maintainMenu");
        }
        boolean success = menuService.removeById(id);
        return success ? Result.ok("Deleted successfully") : Result.error("Failed to delete");
    }
}
